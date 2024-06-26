package blinky.run

import better.files.File
import blinky.BuildInfo
import blinky.run.Instruction._
import blinky.run.config.{FileFilter, MutationsConfigValidated, SimpleBlinkyConfig}
import blinky.run.modules.CliModule
import blinky.v0.BlinkyConfig
import os.{Path, RelPath}
import zio.{ExitCode, ZIO, ZLayer}

import scala.util.Try

class Run(runMutations: RunMutations, pwd: File) {

  private val ruleName = "Blinky"
  private val mutantsOutputFileName = "blinky.mutants"
  private val defaultBlinkyConfFileName = ".scalafix.conf"

  def run(config: MutationsConfigValidated): Instruction[ExitCode] = {
    val originalProjectRoot = Path(pwd.path.toAbsolutePath)
    val originalProjectRelPath =
      Try(Path(config.projectPath.pathAsString).relativeTo(originalProjectRoot))
        .getOrElse(RelPath(config.projectPath.pathAsString))
    val originalProjectPath = originalProjectRoot / originalProjectRelPath
    makeTemporaryFolder.flatMap {
      case Left(error) =>
        printErrorLine(
          s"""Error creating temporary folder:
             |$error
             |""".stripMargin
        ).map(_ => ExitCode.failure)
      case Right(cloneProjectTempFolder) =>
        for {
          _ <-
            when(config.options.verbose)(
              printLine(s"Temporary project folder: $cloneProjectTempFolder")
            )
          runResult <-
            runResultEither(
              "git",
              Seq("rev-parse", "--show-toplevel"),
              path = originalProjectRoot
            ).flatMap {
              case Left(commandError) =>
                ConsoleReporter.gitFailure(commandError)
              case Right(gitRevParse) =>
                val gitFolder: Path = Path(gitRevParse)
                val cloneProjectBaseFolder: Path = cloneProjectTempFolder / gitFolder.baseName
                val projectRealRelPath: RelPath = originalProjectPath.relativeTo(gitFolder)
                val projectRealPath: Path = cloneProjectBaseFolder / projectRealRelPath

                runGitProject(
                  config,
                  gitFolder,
                  originalProjectRoot,
                  originalProjectPath,
                  cloneProjectTempFolder,
                  cloneProjectBaseFolder,
                  projectRealPath
                )
            }
        } yield runResult
    }
  }

  private def runGitProject(
      config: MutationsConfigValidated,
      gitFolder: Path,
      originalProjectRoot: Path,
      originalProjectPath: Path,
      cloneProjectTempFolder: Path,
      cloneProjectBaseFolder: Path,
      projectRealPath: Path
  ): Instruction[ExitCode] =
    for {
      // TODO check for errors
      _ <- makeDirectory(cloneProjectBaseFolder)

      // Setup files to mutate ('scalafix --diff' does not work like I want...)
      filesToMutateEither <- {
        if (config.options.onlyMutateDiff)
          // maybe copy the .git folder so it can be used by TestMutations, etc?
          // cp(gitFolder / ".git", cloneProjectBaseFolder / ".git")
          runResultEither("git", Seq("rev-parse", config.options.mainBranch), path = gitFolder)
            .flatMap {
              case Left(commandError) =>
                ConsoleReporter.gitFailure(commandError).map(Left(_))
              case Right(mainHash) =>
                runResultEither(
                  "git",
                  Seq("--no-pager", "diff", "--name-only", mainHash),
                  path = gitFolder
                ).flatMap {
                  case Left(commandError) =>
                    ConsoleReporter.gitFailure(commandError).map(Left(_))
                  case Right(diffLines) =>
                    val base: Seq[String] =
                      diffLines
                        .split("\\r?\\n")
                        .toSeq
                        .map(file => cloneProjectBaseFolder / RelPath(file))
                        // TODO why sbt?
                        .filter(file => file.ext == "scala" || file.ext == "sbt")
                        .map(_.toString)

                    if (base.isEmpty)
                      succeed(Right(("", base)))
                    else
                      for {
                        copyResult <- copyFilesToTempFolder(
                          originalProjectRoot,
                          originalProjectPath,
                          projectRealPath
                        )
                        result <- optimiseFilesToMutate(
                          base,
                          copyResult,
                          projectRealPath,
                          config.filesToMutate
                        )
                      } yield result
                }
            }
        else
          for {
            // TODO check for errors
            _ <- copyFilesToTempFolder(
              originalProjectRoot,
              originalProjectPath,
              projectRealPath
            )
            processResult <- processFilesToMutate(
              projectRealPath,
              config.filesToMutate
            )
          } yield processResult.map((_, Seq("all")))
      }

      runResult <- filesToMutateEither match {
        case Left(exitCode) =>
          succeed(exitCode)
        case Right((_, Seq())) =>
          ConsoleReporter.filesToMutateIsEmpty
            .map(_ => ExitCode.success)
        case Right((filesToMutateStr, filesToMutateSeq)) =>
          for {
            // TODO: This should stop blinky from running if there is an error.
            coursier <- Setup.setupCoursier(projectRealPath)
            _ <- Setup.sbtCompileWithSemanticDB(projectRealPath)

            // Setup BlinkyConfig object
            blinkyConf: BlinkyConfig = BlinkyConfig(
              mutantsOutputFile = (projectRealPath / mutantsOutputFileName).toString,
              filesToMutate = filesToMutateSeq,
              specificMutants = config.options.mutant,
              enabledMutators = config.mutators.enabled,
              disabledMutators = config.mutators.disabled
            )

            scalaFixConf: String =
              SimpleBlinkyConfig.blinkyConfigEncoder
                .write(blinkyConf)
                .show
                .trim

            scalafixConfFile = cloneProjectTempFolder / defaultBlinkyConfFileName

            // Setup our .blinky.conf file to be used by Blinky rule
            runResult <-
              writeFile(
                scalafixConfFile,
                s"""rules = $ruleName
                   |Blinky $scalaFixConf""".stripMargin
              ).flatMap {
                case Left(error) =>
                  printErrorLine(
                    s"""Error creating temporary folder:
                       |$error
                       |""".stripMargin
                  ).map(_ => ExitCode.failure)
                case Right(_) =>
                  runResultEither(
                    coursier,
                    Seq(
                      "fetch",
                      s"com.github.rcmartins:${ruleName.toLowerCase}_${BuildInfo.scalaMinorVersion}:${BuildInfo.version}",
                      "-p"
                    ),
                    Map(
                      "COURSIER_REPOSITORIES" -> "ivy2Local|sonatype:snapshots|sonatype:releases"
                    ),
                    path = projectRealPath
                  ).flatMap {
                    case Left(commandError) =>
                      ConsoleReporter.gitFailure(commandError)
                    case Right(toolPath) =>
                      val params: Seq[String] =
                        Seq(
                          "launch",
                          "scalafix",
                          "--",
                          if (config.options.verbose) "--verbose" else "",
                          if (config.filesToExclude.nonEmpty)
                            s"--exclude=${config.filesToExclude}"
                          else
                            "",
                          s"--tool-classpath=$toolPath",
                          s"--files=$filesToMutateStr",
                          s"--config=$scalafixConfFile",
                          "--auto-classpath=target"
                        ).filter(_.nonEmpty)
                      for {
                        _ <- printLine(toolPath)
                        // TODO check for errors:
                        _ <- runStream(coursier, params, path = projectRealPath)
                        runResult <- runMutations.run(
                          projectRealPath,
                          blinkyConf.mutantsOutputFile,
                          config.options
                        )
                      } yield runResult
                  }
              }
          } yield runResult
      }
    } yield runResult

  private[run] def processFilesToMutate(
      projectRealPath: Path,
      filesToMutate: FileFilter
  ): Instruction[Either[ExitCode, String]] =
    filesToMutate match {
      case FileFilter.SingleFileOrFolder(fileOrFolder) =>
        succeed(Right(fileOrFolder.toString))
      case FileFilter.FileName(fileName) =>
        lsFiles(projectRealPath).flatMap {
          case Left(throwable) =>
            printErrorLine(
              s"""Failed to list files in $projectRealPath
                 |$throwable
                 |""".stripMargin
            ).map(_ => Left(ExitCode.failure))
          case Right(files) =>
            filterFiles(files, fileName)
        }
    }

  private[run] def optimiseFilesToMutate(
      base: Seq[String],
      copyResult: Either[ExitCode, Unit],
      projectRealPath: Path,
      filesToMutate: FileFilter
  ): Instruction[Either[ExitCode, (String, Seq[String])]] =
    copyResult match {
      case Left(result) =>
        succeed(Left(result))
      case Right(_) => // This part is just an optimization of 'base'
        for {
          fileToMutateResult <- filesToMutate match {
            case FileFilter.SingleFileOrFolder(fileOrFolder) =>
              succeed(Right(projectRealPath / fileOrFolder))
            case FileFilter.FileName(fileName) =>
              filterFiles(base, fileName).map(_.map(Path(_)))
          }
          result <-
            fileToMutateResult match {
              case Left(exitCode) =>
                succeed(Left(exitCode))
              case Right(configFileOrFolderToMutate) =>
                val configFileOrFolderToMutateStr =
                  configFileOrFolderToMutate.toString
                IsFile(
                  configFileOrFolderToMutate,
                  if (_)
                    succeed(base.filter(_ == configFileOrFolderToMutateStr))
                  else
                    succeed(base.filter(_.startsWith(configFileOrFolderToMutateStr)))
                ).map(baseFiltered => Right((configFileOrFolderToMutateStr, baseFiltered)))
            }
        } yield result
    }

  private def filterFiles(
      files: Seq[String],
      fileName: String
  ): Instruction[Either[ExitCode, String]] = {
    val filesFiltered: Seq[String] =
      files.collect { case file if file.endsWith(fileName) => file }
    filesFiltered match {
      case Seq() =>
        printLine(s"--filesToMutate '$fileName' does not exist.")
          .map(_ => Left(ExitCode.failure))
      case Seq(singleFile) =>
        succeed(Right(singleFile))
      case _ =>
        printLine(
          s"""--filesToMutate is ambiguous.
             |Files ending with the same path:
             |${filesFiltered.mkString("\n")}""".stripMargin
        ).map(_ => Left(ExitCode.failure))
    }
  }

  private[run] def copyFilesToTempFolder(
      originalProjectRoot: Path,
      originalProjectPath: Path,
      projectRealPath: Path
  ): Instruction[Either[ExitCode, Unit]] =
    for {
      // Copy only the files tracked by git into our temporary folder
      gitResultEither <- runResultEither(
        "git",
        Seq("ls-files", "--others", "--exclude-standard", "--cached"),
        path = originalProjectPath
      )
      result <- gitResultEither match {
        case Left(commandError) =>
          ConsoleReporter.gitFailure(commandError).map(Left(_))
        case Right(gitResult) =>
          val filesToCopy: Seq[RelPath] =
            gitResult.split("\\r?\\n").map(RelPath(_)).toSeq
          for {
            copyResult <- copyRelativeFiles(
              filesToCopy,
              originalProjectRoot,
              projectRealPath
            )
            _ <- copyResult match {
              case Left(error) => printLine(s"Error copying project files: $error")
              case Right(())   => empty
            }
          } yield Right(())
      }
    } yield result

}

object Run {

  def live: ZLayer[CliModule with RunMutations, Nothing, Run] =
    ZLayer(
      for {
        pwd <- ZIO.serviceWithZIO[CliModule](_.pwd)
        runMutations <- ZIO.service[RunMutations]
      } yield new Run(runMutations, pwd)
    )

}

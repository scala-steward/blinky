package blinky.internal

import java.util.concurrent.atomic.AtomicInteger

import better.files.File
import blinky.v0.BlinkyConfig
import metaconfig.Configured
import play.api.libs.json.Json
import scalafix.v1._

import scala.meta._
import scala.meta.inputs.Input.VirtualFile

class Blinky(config: BlinkyConfig) extends SemanticRule("Blinky") {
  private val mutationId: AtomicInteger = new AtomicInteger(1)
  private val mutantsOutputFileOpt: Option[File] =
    Some(config.mutantsOutputFile).filter(_.nonEmpty).map(File(_))
  mutantsOutputFileOpt.foreach(_.createFileIfNotExists())

  private def nextIndex: Int = mutationId.getAndIncrement()

  private val fileShouldBeMutated: String => Boolean =
    if (config.filesToMutate == Seq("all"))
      (_: String) => true
    else
      config.filesToMutate.toSet

  def this() = this(BlinkyConfig.default)

  override def withConfiguration(config: Configuration): Configured[Rule] =
    config.conf
      .getOrElse(name.value)(BlinkyConfig.default)
      .map(new Blinky(_))

  override def fix(implicit doc: SemanticDocument): Patch = {
    val findMutations: FindMutations = new FindMutations(config.activeMutators, doc)

    val fileName =
      doc.input match {
        case VirtualFile(path, _) => path
        case _                    => ""
      }

    if (!fileShouldBeMutated(fileName))
      Patch.empty
    else {
      def createPatch(
          mutantSeq: Seq[Mutant],
          needsParens: Boolean
      ): Option[(Patch, Seq[Mutant])] = {
        mutantSeq match {
          case Mutant(_, _, original, _) +: _ =>
            val (_, mutatedStr) =
              mutantSeq.map(mutant => (mutant.id, mutant.mutated)).foldRight((0, original)) {
                case ((id, mutatedTerm), (_, originalTerm)) =>
                  val mutantId = Lit.String(s"SCALA_MUTATION_$id")
                  val result =
                    q"""if (_root_.scala.sys.env.contains($mutantId)) ($mutatedTerm) else ($originalTerm)"""
                  (0, result)
              }

            val finalSyntax = if (needsParens) "(" + mutatedStr.syntax + ")" else mutatedStr.syntax
            Some(Patch.replaceTree(original, finalSyntax), mutantSeq)
          case _ =>
            None
        }
      }

      val (finalPatch, mutantsFound): (Seq[Patch], Seq[Seq[Mutant]]) =
        findMutations
          .topTreeMutations(doc.tree)
          .flatMap {
            case (original, MutatedTerms(mutationsFound, needsParens)) =>
              val mutantSeq =
                mutationsFound
                  .filterNot(_.syntax == original.syntax)
                  .map(mutated => createMutant(original, mutated, fileName))
              createPatch(mutantSeq, needsParens = needsParens)
          }
          .unzip

      saveNewMutantsToFile(mutantsFound.flatten)
      finalPatch.asPatch
    }
  }

  def createMutant(original: Term, mutated: Term, fileName: String): Mutant = {
    val mutantIndex = nextIndex
    val pos = original.pos
    val input = pos.input.text

    val startDiffBefore = pos.start - pos.startColumn
    val endDiffBefore = {
      val p = input.indexOf("\n", pos.end)
      if (p == -1) input.length else p
    }
    val mutatedInput = input.substring(0, pos.start) + mutated.syntax + input.substring(pos.end)
    val startDiffAfter = startDiffBefore
    val endDiffAfter = {
      val p = mutatedInput.indexOf("\n", pos.start + mutated.syntax.length)
      if (p == -1) mutatedInput.length else p
    }

    def addLineNumbers(
        startLine: Int,
        linesBefore: List[String],
        linesAfter: List[String]
    ): List[String] = {
      val fileDiffSize = 1 + Math
        .log10(startLine + Math.max(linesBefore.size, linesAfter.size))
        .toInt
      linesBefore.zipWithIndex.map {
        case (line, index) => ("%" + fileDiffSize + "d: %s").format(startLine + index + 1, line)
      } ++
        linesAfter.zipWithIndex.map {
          case (line, index) => ("%" + fileDiffSize + "d: %s").format(startLine + index + 1, line)
        }
    }

    val diffLines: List[String] =
      fileName +:
        addLineNumbers(
          pos.startLine,
          input
            .substring(startDiffBefore, endDiffBefore)
            .split("\n")
            .map(_.stripSuffix("\r"))
            .toList
            .map("-" + _),
          mutatedInput
            .substring(startDiffAfter, endDiffAfter)
            .split("\n")
            .map(_.stripSuffix("\r"))
            .toList
            .map("+" + _)
        )

    Mutant(mutantIndex, diffLines, original, mutated)
  }

  def saveNewMutantsToFile(mutantsFound: Seq[Mutant]): Unit = {
    if (mutantsFound.nonEmpty) {
      mutantsOutputFileOpt.foreach { mutantsOutputFile =>
        val jsonMutationReport = mutantsFound.map(Json.toJson(_)).mkString("", "\n", "\n")
        mutantsOutputFile.append(jsonMutationReport)
      }
    }
  }
}

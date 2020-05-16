package blinky.run

import blinky.BuildInfo.version
import blinky.v0.BlinkyConfig
import metaconfig.generic.Surface
import metaconfig.typesafeconfig._
import metaconfig.{Conf, ConfDecoder, generic}

case class MutationsConfig(
    projectPath: String,
    projectName: String,
    filesToMutate: String,
    conf: BlinkyConfig,
    blinkyVersion: String,
    options: OptionsConfig
)

object MutationsConfig {
  val default: MutationsConfig = MutationsConfig(
    projectPath = ".",
    projectName = "",
    filesToMutate = "src/main/scala",
    conf = BlinkyConfig(""),
    blinkyVersion = version,
    options = OptionsConfig()
  )
  implicit val surface: Surface[MutationsConfig] =
    generic.deriveSurface[MutationsConfig]
  implicit val decoder: ConfDecoder[MutationsConfig] =
    generic.deriveDecoder(default)

  def read(conf: String): MutationsConfig = {
    val original = decoder.read(Conf.parseString(conf)).get
    val projectName = original.projectName
    if (projectName.isEmpty)
      original
    else
      original.copy(
        options = original.options.copy(
          compileCommand = projectName,
          testCommand = projectName
        )
      )
  }
}

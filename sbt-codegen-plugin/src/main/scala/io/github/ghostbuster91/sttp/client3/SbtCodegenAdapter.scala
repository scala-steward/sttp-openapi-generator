package io.github.ghostbuster91.sttp.client3

import org.scalafmt.interfaces.Scalafmt
import sbt._
import sbt.internal.util.ManagedLogger

import java.nio.file.Paths

class SbtCodegenAdapter(
    config: CodegenConfig,
    targetDirectory: File,
    topLevelInputPath: File,
    log: ManagedLogger,
    scalafmt: Scalafmt
) {

  private lazy val codegen = new Codegen(new SbtLogAdapter(log), config)

  def processSingleFile(
      inputFile: File
  ): Either[String, File] = {
    log.info(
      s"[SttpOpenapi] Generating classes for ${inputFile.getAbsolutePath}..."
    )
    val swaggerYaml = IO.read(inputFile)
    val relativePath = Option(
      IO
        .relativizeFile(topLevelInputPath, inputFile)
        .getOrElse(
          throw new IllegalArgumentException(
            s"Given $inputFile is not a descendant of $topLevelInputPath"
          )
        )
        .getParent
    )
    codegen
      .generate(
        swaggerYaml,
        relativePath.map(_.replace("/", ".")).filterNot(_.isEmpty)
      )
      .map { code =>
        val targetFile =
          targetDirectory / relativePath.getOrElse(
            "."
          ) / s"${snakeToCamelCase(inputFile.base)}.scala"
        IO.write(targetFile, format(scalafmt, code.toString(), targetFile))
        targetFile
      }
  }

  private def format(scalafmt: Scalafmt, code: String, futureFile: File) = {
    val scalafmtConfig = Paths.get(".scalafmt.conf")
    if (scalafmtConfig.toFile.exists()) {
      log.info(s"[SttpOpenapi] Formatting ${futureFile.getAbsolutePath}")
      scalafmt.format(scalafmtConfig, futureFile.toPath, code)
    } else {
      code
    }
  }

  private def snakeToCamelCase(snake: String) =
    snake.split('_').toList.map(_.capitalize).mkString
}

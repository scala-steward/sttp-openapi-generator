package io.github.ghostbuster91.sttp.client3

import io.github.ghostbuster91.sttp.client3.SbtCodegenAdapter.FileOpts
import sbt.{AutoPlugin, Def, File}
import org.scalafmt.interfaces.Scalafmt
import sbt.internal.util.ManagedLogger
import sbt.Keys._
import sbt._

object SttpOpenApiCodegenPlugin extends AutoPlugin {

  val sttpOpenApiOutputPath =
    settingKey[File](
      "Directory for sources generated by sttp-openapi generator"
    )
  val sttpOpenApiInput =
    settingKey[Seq[Input]]("Input resources for sttp-openapi generator")

  val sttpOpenApiJsonLibrary =
    settingKey[JsonLibrary]("Json library for sttp-openapi generator to use")

  val sttpOpenApiHandleErrors = settingKey[Boolean](
    "If true the generator will include error information in types"
  )
  val sttpOpenApiMinimizeOutput = settingKey[Boolean](
    "If true the generator will render model classes only if they are referenced by any of the exiting operations"
  )

  val sttpOpenApiTypesMapping =
    settingKey[TypesMapping](
      "Configuration settings for sttp-openapi generator to use"
    )

  object autoImport {

    lazy val generateSources =
      Def.task {
        val log = streams.value.log
        val targetDirectory = sttpOpenApiOutputPath.value
        val topLevelInputPath = sttpOpenApiInput.value
        val scalafmt = Scalafmt.create(this.getClass.getClassLoader)
        val config = CodegenConfig(
          handleErrors = sttpOpenApiHandleErrors.value,
          sttpOpenApiJsonLibrary.value,
          sttpOpenApiMinimizeOutput.value,
          sttpOpenApiTypesMapping.value
        )
        val codegen = new SbtCodegenAdapter(
          config,
          targetDirectory,
          log,
          scalafmt
        )

        val scalaVer = scalaVersion.value
        val inputFiles = collectInputs(topLevelInputPath, log).toMap
        val cachedFun = FileFunction.cached(
          streams.value.cacheDirectory / s"sttp-openapi-src-$scalaVer",
          FileInfo.hash
        ) { input: Set[File] =>
          input.foldLeft(Set.empty[File]) { (result, inputFile) =>
            val generationResult =
              codegen.processSingleFile(inputFile, inputFiles(inputFile))
            generationResult match {
              case Left(failure) =>
                log.warn(s"Couldn't process $inputFile because of $failure")
                result
              case Right(success) => result + success
            }
          }
        }
        cachedFun(inputFiles.keySet).toSeq
      }

    private def collectInputs(
        inputFiles: Seq[Input],
        log: ManagedLogger
    ): List[(File, FileOpts)] =
      inputFiles.toList.flatMap {
        case Input.SingleFile(f, pkg) =>
          if (f.exists()) {
            List((f, FileOpts(pkg = Some(pkg))))
          } else {
            log.warn(
              s"[SttpOpenapi] Input directory $f does not exist. Skipping generation..."
            )
            List.empty
          }
        case Input.Directory(topLevelDir, basePkg) =>
          collectInputFiles(topLevelDir, log).map(file =>
            relativePathToPackage(topLevelDir, file, basePkg)
          )
      }

    private def relativePathToPackage(
        topLevel: File,
        file: File,
        basePkg: Option[String]
    ) = {
      val relativePath = Option(
        IO
          .relativizeFile(topLevel, file)
          .getOrElse(
            throw new IllegalArgumentException(
              s"Given $file is not a descendant of $topLevel"
            )
          )
          .getParent
      )
      val calculatedPkg =
        relativePath.map(_.replace("/", ".")).filterNot(_.isEmpty)
      val fullPkg = (basePkg, calculatedPkg) match {
        case (Some(b), Some(p)) => Some(s"$b.$p")
        case (Some(b), None)    => Some(b)
        case (None, Some(p))    => Some(p)
        case (None, None)       => None
      }
      (file, FileOpts(pkg = fullPkg))
    }

    private def collectInputFiles(f: File, log: ManagedLogger): List[File] =
      if (f.exists()) {
        val these = f.listFiles
        these
          .filter(f => f.isFile && (f.ext == "yaml" || f.ext == "yml"))
          .toList ++ these
          .filter(_.isDirectory)
          .flatMap(collectInputFiles(_, log))
      } else {
        log.warn(
          s"[SttpOpenapi] Input directory $f does not exist. Skipping generation..."
        )
        List.empty
      }
  }

  import autoImport._

  private lazy val coreDeps = List(
    "com.softwaremill.sttp.client3" %% "core" % "3.3.18"
  )

  private lazy val circeDeps = List(
    "io.circe" %% "circe-core" % "0.14.2",
    "io.circe" %% "circe-parser" % "0.14.2",
    "com.softwaremill.sttp.client3" %% "circe" % "3.3.18"
  )

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      sttpOpenApiOutputPath := (Compile / sourceManaged).value,
      sttpOpenApiInput := Seq(
        Input.Directory((Compile / resourceDirectory).value, None)
      ),
      sttpOpenApiJsonLibrary := JsonLibrary.Circe,
      sttpOpenApiHandleErrors := true,
      sttpOpenApiMinimizeOutput := true,
      Compile / sourceGenerators += generateSources.taskValue,
      libraryDependencies ++= coreDeps ++ (sttpOpenApiJsonLibrary.value match {
        case JsonLibrary.Circe => circeDeps
      }),
      sttpOpenApiTypesMapping := TypesMapping()
    )

  sealed trait Input
  object Input {
    case class SingleFile(file: File, pkg: String) extends Input
    case class Directory(directory: File, basePkg: Option[String]) extends Input
  }
}

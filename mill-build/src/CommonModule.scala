package millbuild

import mill.*
import com.goyeau.mill.scalafix.ScalafixModule
import mill.contrib.scoverage.ScoverageModule
import mill.javalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.scalalib.{PlatformScalaModule, ScalaModule}
import mill.scalalib.scalafmt.ScalafmtModule

trait CommonBuildModule
    extends ScalaModule,
      PlatformScalaModule,
      ScalafixModule,
      ScalafmtModule,
      ScoverageModule {
  final def v: Version.type = Version

  def jvmVersion = "17"

  final def scoverageVersion = "2.5.2"

  final def scalaVersion = v.scala

  def scalacOptions = Seq(
    "-release",
    "17",
    "-Wunused:all",
    "-Xcheck-macros",
    "-explain",
    "-opt", // the scala 3 optimizer for libraries
  )
}

trait CommonModule extends CommonBuildModule, GitVersionPublishModule {
  final def pomSettings = PomSettings(
    description =
      "A Scala 3 cross-platform library and CLI for LinkML schema validation and multi-format code generation (Scala, JSON Schema, SHACL, RDFS)",
    organization = "eu.neverblink.linkml",
    url = "https://github.com/NeverBlink-OSS/ohi",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("NeverBlink-OSS", "linkml-scala"),
    Seq(
      Developer(
        id = "NeverBlink",
        name = "NeverBlink",
        url = "https://neverblink.eu",
        organization = Some("NeverBlink"),
        organizationUrl = Some("https://neverblink.eu"),
      ),
    ),
  )
}

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

  /** Removes Inkuire, the search by type signature, which does nothing without
    * `-Ygenerate-inkuire`. The search bar starts its worker on every page, hence the empty worker.
    */
  override def scalaDocGenerated: T[PathRef] = Task {
    val docs = Task.dest / "docs"
    os.copy(super.scalaDocGenerated().path, docs)
    os.remove(docs / "scripts" / "inkuire.js")
    os.write.over(docs / "scripts" / "inkuire-worker.js", "")
    PathRef(docs)
  }

  final def pomSettings = PomSettings(
    description =
      "A Scala 3 cross-platform library and CLI for LinkML schema validation and multi-format code generation (Scala, JSON Schema, SHACL, RDFS)",
    organization = CommonModule.organization,
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

object CommonModule {
  val organization = "eu.neverblink.linkml"
}

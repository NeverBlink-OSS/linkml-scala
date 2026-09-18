package millbuild

import mill.*
import com.goyeau.mill.scalafix.ScalafixModule
import mill.contrib.scoverage.ScoverageModule
import mill.javalib.PublishModule
import mill.javalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.scalalib.{PlatformScalaModule, ScalaModule}
import mill.scalalib.scalafmt.ScalafmtModule

trait CommonModule
    extends ScalaModule,
      PublishModule,
      PlatformScalaModule,
      ScalafixModule,
      ScalafmtModule,
      ScoverageModule {
  final def v: Version.type = Version

  def jvmVersion = "17"

  final def scoverageVersion = "2.5.2"

  final def pomSettings = PomSettings(
    description =
      "A Scala 3 cross-platform library and CLI for LinkML schema validation and multi-format code generation (Scala, JSON Schema, SHACL, RDFS)",
    organization = "eu.neverblink.linkml",
    url = "https://github.com/NeverBlink-OSS/linkml-scala",
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

  final def publishVersion = Task {
    // On a tag build, use the tag that triggered the release rather than
    // `git describe`, which is ambiguous when several v* tags point at the same
    // commit and can otherwise publish the wrong version.
    val tagVersion =
      if sys.env.get("GITHUB_REF_TYPE").contains("tag") then
        sys.env.get("GITHUB_REF_NAME").filter(_.startsWith("v")).map(_.stripPrefix("v"))
      else None
    tagVersion.getOrElse(describeVersion())
  }

  private def describeVersion(): String =
    try describeVersionUnsafe()
    catch {
      // No tags, shallow clone or not a git checkout
      case _: os.SubprocessException => "0.0.0-SNAPSHOT"
    }

  private def describeVersionUnsafe(): String = {
    val gitOutput = os.proc("git", "describe", "--tags", "--long", "--match", "v*")
      .call(cwd = mill.api.BuildCtx.workspaceRoot, stderr = os.Pipe)
      .out
      .trim()
    val GitDescribeRegex = """v(.*)-(\d+)-g([0-9a-fA-F]+)""".r
    gitOutput match {
      case GitDescribeRegex(tag, commitCount, shortHash) =>
        if (commitCount == "0") tag
        else {
          val targetCommit = os.proc("git", "rev-parse", s"HEAD~$commitCount")
            .call(cwd = mill.api.BuildCtx.workspaceRoot).out.trim()
          val tagsAtCommit = os.proc("git", "tag", "--points-at", targetCommit)
            .call(cwd = mill.api.BuildCtx.workspaceRoot).out.trim().linesIterator.toList
          val latestTag = tagsAtCommit
            .filter(_.startsWith("v"))
            .map(_.stripPrefix("v"))
            .sorted(using Ordering.String.reverse)
            .headOption
            .getOrElse(tag)
          s"$latestTag-$commitCount-$shortHash-SNAPSHOT"
        }
      case other =>
        s"$other-SNAPSHOT"
    }
  }

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

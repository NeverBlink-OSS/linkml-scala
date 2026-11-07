package millbuild

import mill.*
import mill.javalib.PublishModule

/** A published module whose version comes from the git tags: the tag on a release build, and
  * otherwise a snapshot version made from `git describe`.
  */
trait GitVersionPublishModule extends PublishModule {

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
}

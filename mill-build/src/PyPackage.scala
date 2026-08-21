package millbuild

/** Metadata for the `neverblink-linkml` PyPI package. */
object PyPackage {
  val name = "neverblink-linkml"
  // `0.13.2-3-abc1234-SNAPSHOT`
  private val Snapshot = """(.+?)-(\d+)-([0-9a-fA-F]+)-SNAPSHOT""".r
  // `0.13.2-SNAPSHOT`, when there is no commit hash
  private val PlainSnapshot = """(.+?)-SNAPSHOT""".r
  private val PreRelease = """(.+?)-(a|alpha|b|beta|rc|RC)\.?(\d+)""".r
  private val Release = """\d+(?:\.\d+)*""".r

  /** Rewrite a Mill publish version into something compliant with PEP 440.
    */
  def version(millVersion: String): String = millVersion match {
    case Snapshot(tag, commits, hash) => s"${release(tag)}+$commits.g$hash"
    case PlainSnapshot(tag) => s"${release(tag)}+dev"
    case tag => release(tag)
  }

  /** The release part on its own, without any snapshot suffix. */
  private def release(tag: String): String = tag match {
    case PreRelease(base, kind, number) => s"$base${marker(kind)}$number"
    case Release() => tag
    // A checkout with no tags at all, so there is no version to derive. Nothing to publish either.
    case _ => "0.0.0"
  }

  private def marker(kind: String): String = kind.toLowerCase match {
    case "a" | "alpha" => "a"
    case "b" | "beta" => "b"
    case _ => "rc"
  }

  /** The `_version.py` that goes into the wheel. */
  def versionFile(version: String): String =
    s"""\"\"\"The package version, written by `nativelib.pythonWheel` in build.mill.\"\"\"
       |
       |__version__ = "$version"
       |""".stripMargin
}

package millbuild

/** Compiling LinkML to a native shared library with a C ABI, via native-image.
  *
  * Plain functions taking paths rather than Mill tasks, for the same reason [[Python]] is shaped
  * that way: Mill's macro only allows task lookups directly inside a `Task {...}` block, so
  * build.mill resolves what it needs and these do the work.
  */
object NativeLib {

  /** The library's base name. native-image adds the platform's prefix and extension. */
  val name = "liblinkml_scala"

  /** Join classpath entries the way the JVM on this platform expects. */
  def classPath(entries: Seq[os.Path]): String =
    entries.map(_.toString).mkString(java.io.File.pathSeparator)

  /** The classpath written to a file, so nativelib/build-shared.sh can pass it on without a command
    * line long enough to worry about. Returns the file.
    */
  def classPathFile(dest: os.Path, entries: Seq[os.Path]): os.Path = {
    val file = dest / "classpath.txt"
    os.write.over(file, classPath(entries))
    file
  }

  /** Build the library on this machine, for this machine, into `dest`. Takes a few minutes.
    *
    * This is what macOS and Windows ship, and what a source checkout gets. Linux ships the builds
    * from [[buildForLinux]] instead.
    */
  def build(
      dest: os.Path,
      graalvmHome: os.Path,
      entries: Seq[os.Path],
      options: Seq[String],
      repoRoot: os.Path,
  ): os.Path = {
    val ext = if scala.util.Properties.isWin then ".cmd" else ""
    os.call(
      (
        (graalvmHome / "bin" / s"native-image$ext").toString,
        "--shared",
        options,
        s"-H:Path=$dest",
        s"-H:Name=$name",
        "-cp",
        classPath(entries),
      ),
      cwd = repoRoot,
      stdout = os.Inherit,
      stderr = os.Inherit,
    )
    dest
  }

  /** Build the library for one Linux libc, through nativelib/build-shared.sh. Returns `dest`.
    *
    * `libc` is `glibc`, built inside manylinux2014 so that the minimum glibc is as low as it goes,
    * or `musl`, cross-linked for Alpine. The script explains both.
    */
  def buildForLinux(
      libc: String,
      dest: os.Path,
      graalvmHome: os.Path,
      classPathFile: os.Path,
      options: Seq[String],
      repoRoot: os.Path,
  ): os.Path = {
    os.call(
      (
        "bash",
        (repoRoot / "nativelib" / "build-shared.sh").toString,
        libc,
        graalvmHome.toString,
        dest.toString,
        classPathFile.toString,
        options,
      ),
      cwd = repoRoot,
      stdout = os.Inherit,
      stderr = os.Inherit,
    )
    dest
  }
}

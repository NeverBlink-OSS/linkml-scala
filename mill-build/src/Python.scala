package millbuild

/** Helper functions for Python packaging and testing.
  */
object Python {
  def executable: String =
    sys.env.getOrElse("PYTHON", if scala.util.Properties.isWin then "python" else "python3")

  /** Copy a native-image build, headers and all, into where the package looks for the library.
    */
  def installLibrary(built: os.Path, libDir: os.Path): Unit = {
    os.makeDir.all(libDir)
    os.list(built).filter(os.isFile).foreach(os.copy.into(_, libDir, replaceExisting = true))
  }

  /** Run the binding test suite, against whichever library is installed in the package. */
  def runTests(repoRoot: os.Path): Unit =
    os.call(
      (executable, "-m", "unittest", "discover", "-s", "python", "-v"),
      cwd = repoRoot,
      stdout = os.Inherit,
      stderr = os.Inherit,
    )

  /** Assemble the package under `dest` and build a wheel from it. Returns the directory holding it.
    *
    * @param built
    *   the native-image output directory to take the shared library from
    * @param source
    *   the repository's `python/` directory
    * @param license
    *   the license file to ship alongside the code
    * @param version
    *   the wheel's version, in the PEP 440 format
    */
  def buildWheel(
      dest: os.Path,
      built: os.Path,
      source: os.Path,
      license: os.Path,
      version: String,
  ): os.Path = {
    val staging = dest / "package"
    val dist = dest / "dist"

    os.makeDir.all(staging / "ohi_linkml")
    Seq("pyproject.toml", "README.md", "hatch_build.py")
      .foreach(name => os.copy.into(source / name, staging))
    os.copy.into(license, staging)
    os.list(source / "ohi_linkml")
      .filter(path => os.isFile(path) && (path.ext == "py" || path.last == "py.typed"))
      .foreach(os.copy.into(_, staging / "ohi_linkml"))

    os.write.over(staging / "ohi_linkml" / "_version.py", PyPackage.versionFile(version))

    // Only the library itself: the headers beside it are for C callers, and ctypes has no use for
    // the Windows import library.
    val libraries = os.list(built)
      .filter(path => os.isFile(path) && Set("so", "dylib", "dll").contains(path.ext))
    val library = libraries match {
      case Seq(only) => only
      case found =>
        throw new Exception(s"expected one shared library in $built, found ${found.mkString(", ")}")
    }
    os.makeDir.all(staging / "ohi_linkml" / "_lib")
    os.copy.into(library, staging / "ohi_linkml" / "_lib")

    println(s"Building the ${PyPackage.name} $version wheel from ${library.last}")
    os.call(
      (executable, "-m", "build", "--wheel", "--outdir", dist.toString, staging.toString),
      stdout = os.Inherit,
      stderr = os.Inherit,
    )
    dist
  }

  /** Install the wheel from `dist` into a fresh virtualenv under `dest` and run the binding tests
    * against it.
    *
    * @param source
    *   the repository's `python/` directory, holding `test_bindings.py`
    */
  def testWheel(dest: os.Path, dist: os.Path, source: os.Path, repoRoot: os.Path): Unit = {
    val wheel = os.list(dist).find(_.ext == "whl").getOrElse(
      throw new Exception(s"no wheel was built in $dist"),
    )

    val venv = dest / "venv"
    os.call((executable, "-m", "venv", venv.toString), stdout = os.Inherit, stderr = os.Inherit)
    val venvPython =
      if scala.util.Properties.isWin then venv / "Scripts" / "python.exe"
      else venv / "bin" / "python"

    os.call(
      (venvPython.toString, "-m", "pip", "install", "--quiet", wheel.toString),
      stdout = os.Inherit,
      stderr = os.Inherit,
    )

    val importedFrom = os.call(
      (venvPython.toString, "-c", "import ohi_linkml; print(ohi_linkml.__file__)"),
      cwd = dest,
    ).out.trim()
    if !importedFrom.startsWith(venv.toString) then
      throw new Exception(s"ohi_linkml was imported from $importedFrom, not from $venv")

    os.copy.over(source / "test_bindings.py", dest / "test_bindings.py")
    os.call(
      (venvPython.toString, "-m", "unittest", "test_bindings", "-v"),
      cwd = dest,
      env = Map("LINKML_SCALA_REPO" -> repoRoot.toString),
      stdout = os.Inherit,
      stderr = os.Inherit,
    )
  }

  /** Stage the sdist package for upload.
    *
    * @param source
    *   the repository's `python/` directory
    * @param nirJars
    *   the jars holding the Scala Native IR to link
    * @param linkerJars
    *   Scala Native's linker and its dependencies
    */
  def layOutSdist(
      dest: os.Path,
      source: os.Path,
      license: os.Path,
      version: String,
      nirJars: Seq[os.Path],
      linkerJars: Seq[os.Path],
  ): Unit = {
    Seq("pyproject.toml", "hatch_build.py", "README.md")
      .foreach(name => os.copy.over(source / name, dest / name, createFolders = true))
    os.copy.over(license, dest / "LICENSE")
    os.copy.over(source / "ohi_linkml", dest / "ohi_linkml")
    os.remove.all(dest / "ohi_linkml" / "_lib")
    os.write.over(dest / "ohi_linkml" / "_version.py", PyPackage.versionFile(version))

    stageJars(nirJars, dest / "_native" / "nir")
    stageJars(linkerJars, dest / "_native" / "linker")
  }

  /** Copies the jars into `into` as `<n>-<name>`, where `n` is the jar's place on the classpath.
    * hatch_build.py sorts the jars by `n` to restore that order. The order matters because the
    * linker takes the first jar that has a class.
    */
  private def stageJars(jars: Seq[os.Path], into: os.Path): Unit = {
    os.makeDir.all(into)
    var i = 0
    jars.foreach { jar =>
      // Dependency jars are uniquely named; ours are all `out.jar`, so name those by module.
      val name =
        if jar.last == "out.jar" then
          jar.segments.toSeq.dropRight(2).takeRight(2).mkString("-") + ".jar"
        else jar.last
      os.copy.over(jar, into / s"$i-$name")
      i += 1
    }
  }

  /** Build a source distribution from a tree prepared by `layOutSdist`. Returns the directory
    * holding it.
    */
  def buildSdist(dest: os.Path, tree: os.Path): os.Path = {
    os.call(
      (executable, "-m", "build", "--sdist", "--outdir", dest.toString, tree.toString),
      stdout = os.Inherit,
      stderr = os.Inherit,
    )
    dest
  }

  /** Install a source distribution from `dist` into a throwaway virtualenv and run the test suite
    * against it.
    */
  def testSdist(dest: os.Path, dist: os.Path, repoRoot: os.Path): Unit = {
    val sdist = os.list(dist).find(_.ext == "gz").getOrElse(
      throw new Exception(s"no source distribution was built in $dist"),
    )

    val venv = dest / "venv"
    os.call((executable, "-m", "venv", venv.toString), stdout = os.Inherit, stderr = os.Inherit)
    val venvPython =
      if scala.util.Properties.isWin then venv / "Scripts" / "python.exe"
      else venv / "bin" / "python"

    os.call(
      (venvPython.toString, "-m", "pip", "install", sdist.toString),
      stdout = os.Inherit,
      stderr = os.Inherit,
    )

    // From a directory with no ohi_linkml in it, so the installed package is what gets imported.
    os.copy.over(repoRoot / "python" / "test_bindings.py", dest / "test_bindings.py")
    os.call(
      (venvPython.toString, "-m", "unittest", "test_bindings", "-v"),
      cwd = dest,
      env = Map("LINKML_SCALA_REPO" -> repoRoot.toString),
      stdout = os.Inherit,
      stderr = os.Inherit,
    )
  }
}

package eu.neverblink.linkml.optiongen

import java.io.{ByteArrayOutputStream, File}

private[optiongen] object GeneratedScala {
  def compile(root: os.Path, sources: Seq[os.Path]): os.Path = {
    val classes = root / "classes"
    os.makeDir(classes)
    val log = new ByteArrayOutputStream()
    val args = Array("-classpath", System.getProperty("java.class.path"), "-d", classes.toString) ++
      sources.map(_.toString)
    val reporter = Console.withErr(log) {
      Console.withOut(log)(dotty.tools.dotc.Driver().process(args))
    }
    assert(!reporter.hasErrors, s"generated Scala compiler output:\n$log")
    classes
  }

  def run(
      classes: os.Path,
      main: String,
      arguments: Seq[String],
      cwd: os.Path,
  ): os.CommandResult = {
    val javaName = if System.getProperty("os.name").startsWith("Windows") then "java.exe"
    else "java"
    val java = os.Path(System.getProperty("java.home")) / "bin" / javaName
    os.proc(
      java,
      "-cp",
      classes.toString + File.pathSeparator + System.getProperty("java.class.path"),
      main,
      arguments,
    ).call(cwd = cwd, check = false, stderr = os.Pipe)
  }
}

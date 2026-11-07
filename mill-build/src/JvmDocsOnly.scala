package millbuild

import mill.*
import mill.scalalib.ScalaModule
import mill.util.Jvm

/** The docs of a JS or Native module are almost the same as of its JVM module, so its javadoc jar,
  * which Maven Central requires, only links to the JVM docs.
  */
trait JvmDocsOnly extends ScalaModule {
  override def docJar: T[PathRef] = Task {
    val url =
      s"https://javadoc.io/doc/${CommonModule.organization}/${artifactName()}_${artifactScalaVersion()}"
    os.write(
      Task.dest / "docs" / "index.html",
      s"""<!DOCTYPE html>
         |<meta http-equiv="refresh" content="0; url=$url">
         |<p>See the docs of the JVM version: <a href="$url">$url</a></p>
         |""".stripMargin,
      createFolders = true,
    )
    PathRef(Jvm.createJar(Task.dest / "out.jar", Seq(Task.dest / "docs")))
  }
}

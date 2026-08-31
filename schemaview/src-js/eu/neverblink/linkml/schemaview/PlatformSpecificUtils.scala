package eu.neverblink.linkml.schemaview

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@JSImport("fs", JSImport.Namespace)
@js.native
private[schemaview] object FS extends js.Object {
  def readFileSync(path: String, encoding: String): String = js.native
}

private[schemaview] object PlatformSpecificUtils {
  val cwd: String = js.Dynamic.global.process.cwd().asInstanceOf[String]

  def getEnv(name: String): Option[String] =
    if (
      js.typeOf(js.Dynamic.global.process) != "undefined" &&
      js.typeOf(js.Dynamic.global.process.env) != "undefined"
    ) {
      js.Dynamic.global.process.env.asInstanceOf[js.Dictionary[String]].get(name)
    } else None

  def readFile(path: String): String = FS.readFileSync(path, "utf-8")
}

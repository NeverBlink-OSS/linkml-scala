package eu.neverblink.linkml.schemaview

import scala.scalajs.js

private[schemaview] object PlatformSpecificUtils {
  lazy val cwd: String = js.Dynamic.global.process.cwd().asInstanceOf[String]

  def getEnv(name: String): Option[String] =
    if (
      js.typeOf(js.Dynamic.global.process) != "undefined" &&
      js.typeOf(js.Dynamic.global.process.env) != "undefined"
    ) {
      js.Dynamic.global.process.env.asInstanceOf[js.Dictionary[String]].get(name)
    } else None

  /** Looked up when a file is first read rather than with a static `import "fs"`, which would stop
    * the whole bundle from loading in a browser.
    */
  lazy val fs: js.Dynamic =
    if (
      js.typeOf(js.Dynamic.global.process) != "undefined" &&
      js.typeOf(js.Dynamic.global.process.getBuiltinModule) == "function"
    ) {
      js.Dynamic.global.process.getBuiltinModule("fs")
    } else {
      throw UnsupportedOperationException(
        "Reading files needs Node.js 20.16 or newer. In a browser, pass the schemas directly.",
      )
    }
}

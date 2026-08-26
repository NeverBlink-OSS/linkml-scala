package eu.neverblink.linkml.schemaview.buildinfo

import scala.scalajs.js

/** The parts of [[CurrentBuild]] that only the JavaScript build can answer. */
private[buildinfo] object PlatformBuild {

  def platform: Platform = Platform.Js

  /** A rough description of the engine. Node reports its own version. Everything else gets a label,
    * because there is nothing reliable to read.
    */
  def runtime: Option[String] = {
    val nodeVersion =
      if js.typeOf(js.Dynamic.global.process) != "undefined" then {
        // Already read, so these are ordinary property accesses and need no special treatment.
        val versions = js.Dynamic.global.process.versions
        if js.typeOf(versions) != "undefined" && js.typeOf(versions.node) != "undefined" then
          Some(versions.node.asInstanceOf[String])
        else None
      } else None

    nodeVersion match {
      case Some(version) => Some(s"Node.js v$version")
      // `navigator` rather than `document`, so that a worker counts as a browser too.
      case None if js.typeOf(js.Dynamic.global.navigator) != "undefined" => Some("Browser")
      case None => Some("JavaScript")
    }
  }

  def scalaJsVersion: Option[String] = Some(BuildConstants.scalaJsVersion)
}

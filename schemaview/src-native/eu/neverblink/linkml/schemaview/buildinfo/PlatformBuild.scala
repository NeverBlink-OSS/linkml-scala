package eu.neverblink.linkml.schemaview.buildinfo

import scala.scalanative.meta.LinktimeInfo

/** The parts of [[CurrentBuild]] that only the Scala Native build can answer. */
private[buildinfo] object PlatformBuild {

  def platform: Platform = Platform.ScalaNative

  def runtime: Option[String] = {
    import LinktimeInfo.target.*
    Some(s"Scala Native ${LinktimeInfo.runtimeVersion} ($arch-$vendor-$os-$env)")
  }

  def scalaJsVersion: Option[String] = None
}

package eu.neverblink.linkml.schemaview.buildinfo

/** The parts of [[CurrentBuild]] that only the JVM (and the native images built from it) can
  * answer.
  */
private[buildinfo] object PlatformBuild {

  private def inNativeImage: Boolean =
    System.getProperty("org.graalvm.nativeimage.imagecode") ne null

  def platform: Platform = if inNativeImage then Platform.Native else Platform.Jvm

  def runtime: Option[String] = {
    val name = Option(System.getProperty("java.vm.name"))
    val version = Option(System.getProperty("java.vm.version"))
    (name, version) match {
      case (Some(n), Some(v)) => Some(s"$n $v")
      case (Some(n), None) => Some(n)
      case _ => version
    }
  }

  /** Scala.js is not part of a JVM build. */
  def scalaJsVersion: Option[String] = None
}

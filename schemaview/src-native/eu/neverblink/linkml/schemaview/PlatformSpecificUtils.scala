package eu.neverblink.linkml.schemaview

private[schemaview] object PlatformSpecificUtils {
  val cwd: String = System.getProperty("user.dir")

  def getEnv(name: String): Option[String] = Option(System.getenv(name))
}

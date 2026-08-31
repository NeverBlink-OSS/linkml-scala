package eu.neverblink.linkml.schemaview

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets.UTF_8

private[schemaview] object PlatformSpecificUtils {
  val cwd: String = System.getProperty("user.dir")

  def getEnv(name: String): Option[String] = Option(System.getenv(name))

  def readFile(path: String): String = new String(Files.readAllBytes(Paths.get(path)), UTF_8)
}

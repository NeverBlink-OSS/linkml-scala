package millbuild

import mill.javalib.JavaModule
import mill.*

trait ResourcesAsStrings(module: String) extends JavaModule {
  override def generatedSources = Task {

    val maxStrConstLen = 64 * 1000
    val sb = new java.lang.StringBuilder

    sb.append("package eu.neverblink.linkml.").append(module).append("\n")
    sb.append("\n")
    sb.append("object Resources {\n")
    sb.append("  private def decodeBase64(value: String): String =\n")
    sb.append(
      "    new String(java.util.Base64.getDecoder.decode(value), java.nio.charset.StandardCharsets.UTF_8)\n",
    )
    sb.append("\n")
    sb.append("  val map = new java.util.HashMap[String, String]()\n")
    resources().filter(ref => os.exists(ref.path)).foreach { pathRef =>
      os.walk(pathRef.path, followLinks = true).sorted.foreach { path =>
        if (os.isFile(path)) {
          val relPath = "/" + path.relativeTo(pathRef.path).segments.mkString("/")
          sb.append("  map.put(\"").append(relPath).append("\", ")
          val encodedContent =
            java.util.Base64.getEncoder.encodeToString(os.read.bytes(path))
          val encodedContentLen = encodedContent.length
          if (encodedContentLen < maxStrConstLen)
            sb.append("decodeBase64(\"").append(encodedContent).append("\")")
          else {
            sb.append("decodeBase64(new java.lang.StringBuilder()")
            var offset = 0
            while (offset < encodedContentLen) {
              val len = Math.min(offset + maxStrConstLen, encodedContentLen)
              sb.append(".append(\"").append(encodedContent, offset, len).append("\")")
              offset += maxStrConstLen
            }
            sb.append(".toString)")
          }
          sb.append(")\n")
        }
      }
    }
    sb.append("\n")
    sb.append(
      "  def read(path: String): String = Option(map.get(path)).getOrElse(throw new NoSuchElementException(\"Resource not found: \" + path))\n",
    )
    sb.append("}\n")

    val dest = Task.dest / "eu" / "neverblink" / "linkml" / module / "Resources.scala"
    os.write.over(dest, sb.toString, createFolders = true)
    super.generatedSources() :+ PathRef(dest)
  }
}

package eu.neverblink.ohi.catalog

import eu.neverblink.ohi.model.generated.Publication
import eu.neverblink.ohi.model.generated.search.SearchHit
import java.nio.charset.StandardCharsets

/** Links to pages on ohi.dev. */
object OhiPages {

  /** Page of one release on ohi.dev, such as https://ohi.dev/o/w3c/sosa@2017. */
  def releasePage(namespace: String, name: String, version: String): String =
    s"https://ohi.dev/o/${segment(namespace)}/${segment(name)}@${segment(version)}"

  def releasePage(publication: Publication): String =
    releasePage(publication.namespace, publication.name, publication.version)

  def releasePage(hit: SearchHit): String = releasePage(hit.namespace, hit.name, hit.version)

  // Same characters left as they are as JavaScript's encodeURIComponent.
  private val unescaped = (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') ++ "-_.!~*'()").toSet

  private def segment(value: String): String =
    value.getBytes(StandardCharsets.UTF_8).map { byte =>
      val char = (byte & 0xff).toChar
      if (unescaped(char)) char.toString else f"%%${byte & 0xff}%02X"
    }.mkString
}

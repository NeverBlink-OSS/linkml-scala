package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.metamodel.CommonMetadata
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.StringUtils.splitLines

/** The text of a doc comment on a generated element, for the code generators. */
object DocLines {

  /** The element's title and description in the given language, as `title: description` or
    * whichever of the two it has, split into lines. Empty if it has neither.
    */
  def apply(element: CommonMetadata, language: String): Seq[String] = {
    val title = element.title.flatMapFast(_.inLanguage(language))
    val description = element.description.flatMapFast(_.inLanguage(language))
    val text = title match {
      case Some(t) =>
        description match {
          case Some(d) => Some(s"$t: $d")
          case _ => title
        }
      case _ => description
    }
    text.foldFast(Nil: Seq[String])(t => splitLines(t.strip))
  }
}

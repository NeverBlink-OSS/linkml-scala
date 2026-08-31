package eu.neverblink.linkml.runtime

/** ADT for handling text that may be multilingual. In JSON maps to a string or an object. In RDF
  * converts to xsd:string / rdf:langString.
  */
sealed trait LocalizedText:
  /** Try rendering as a single plain string, with the preferred language. If the
    * [[MultilingualText]] does not have this language, returns None. If the text is [[PlainText]]
    * then always returns the value of the plain text.
    */
  def inLanguage(tag: LanguageTag): Option[String]

  /** Render as a single plain string in some way, matching common uses. Prefer using [[inLanguage]]
    * if possible.
    *
    * This will use (1) the plain text if available, (2) the English text if available, or (3) the
    * text of the alphabetically first language tag.
    */
  def plain: String

/** A plain string, which does not carry any language information. For schema
  * backwards-compatibility, and "I don't care" usages.
  */
case class PlainText(value: String) extends LocalizedText:
  override def inLanguage(tag: LanguageTag): Some[String] = Some(value)

  override def plain: String = value

/** Tag of the language, conformant to RFC 5646 (not enforced yet). */
type LanguageTag = String

/** Multi-language text, keyed by the language tag
  * @param mapping
  *   Non-empty mapping of the language tag to the localized text
  */
case class MultilingualText(mapping: Map[LanguageTag, String]) extends LocalizedText:
  assume(mapping.nonEmpty)

  override def inLanguage(tag: LanguageTag): Option[String] = mapping.get(tag)

  override def plain: String =
    mapping.getOrElse("en", mapping.minBy(_._1)._2)

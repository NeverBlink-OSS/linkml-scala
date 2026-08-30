package eu.neverblink.linkml.runtime

/** ADT for handling text that may be multilingual. In JSON maps to a string or an object. In RDF
  * converts to xsd:string / rdf:langString.
  */
sealed trait LocalizedText:
  /** Render as a single plain string, for output formats that don't support multilingual text, such
    * as JSON Schema.
    *
    * This will use (1) the plain text if available, (2) the English text if available, or (3) the
    * text of the alphabetically first language tag. If there is no text at all, it will return an
    * empty string.
    */
  def plain: String

/** A plain string, which does not carry any language information
  */
case class PlainText(value: String) extends LocalizedText:
  override def plain: String = value

/** */
type LanguageTag = String

/** Multi-language text - a mapping of the language tag to the localized text
  */
case class MultilingualText(mapping: Map[LanguageTag, String]) extends LocalizedText:
  override def plain: String =
    mapping.getOrElse("en", if mapping.isEmpty then "" else mapping.minBy(_._1)._2)

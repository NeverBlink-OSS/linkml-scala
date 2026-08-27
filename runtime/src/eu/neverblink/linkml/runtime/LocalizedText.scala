package eu.neverblink.linkml.runtime

/** ADT for handling text that may be multilingual. In JSON maps to a string or an object. In RDF
  * converts to xsd:string / rdf:langString.
  */
sealed trait LocalizedText

/** A plain string, which does not carry any language information
  */
case class PlainText(value: String) extends LocalizedText

/** */
type LangTag = String

/** Multi-language text - a mapping of the language tag to the localized text
  */
case class MultilingualText(mapping: Map[LangTag, String]) extends LocalizedText

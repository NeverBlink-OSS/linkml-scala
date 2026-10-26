package eu.neverblink.linkml.runtime

/** A schema or ontology that can't be imported or written, for a reason the user can fix, such as a
  * URI that would be read back as a CURIE. The CLI prints only its message, without a stack trace.
  */
final class SchemaConversionException(message: String) extends IllegalArgumentException(message)

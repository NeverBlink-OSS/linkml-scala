package eu.neverblink.linkml.generator.scala

/** Scala keywords and reserved words. Keywords may be escaped with backticks (`). Reserved words
  * must be escaped with an underscore instead.
  */
object ScalaWords {
  val keywords: Set[String] = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "extends",
    "final",
    "finally",
    "for",
    "forSome",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "object",
    "override",
    "package",
    "protected",
    "return",
    "sealed",
    "super",
    "this",
    "throw",
    "trait",
    "try",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield",
    "inline",
    "derives",
    "end",
    "extension",
    "using",
    "as",
  )

  val reserved: Set[String] = Set(
    // java.lang.Object
    "hashCode",
    "notify",
    "toString",
    "getClass",
    "notifyAll",
    "synchronized",
    "wait",
    // Scala runtime types
    "String",
    "Int",
    "Float",
    "Double",
    "Boolean",
    "BigDecimal",
    // LinkML runtime types
    "LinkmlAny",
    "LinkmlDate",
    "LinkmlDateTime",
    "LinkmlTime",
    "UriOrCurie",
    "Uri",
    "Curie",
    "NcName",
    "LocalizedText",
    "Unknown",
  )
}

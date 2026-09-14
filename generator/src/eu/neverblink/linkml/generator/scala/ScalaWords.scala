package eu.neverblink.linkml.generator.scala

/** Scala 3.9 LTS keywords and reserved words. Keywords may be escaped with backticks (`). Soft
  * keywords are listed but commented - they can be used in standard identifiers like class, trait,
  * method, or fields. Reserved words must be escaped with an underscore instead.
  */
object ScalaWords {
  val keywords: Set[String] = Set(
    "abstract",
    // "as", soft keyword, example: import scala.collection.mutable.{Map as MutableMap}
    "case",
    "catch",
    "class",
    "def",
    // "derives", soft keyword, example: enum Tree[T] derives CanEqual
    "do",
    "else",
    // "end", soft keyword, example: end MyClass
    "enum",
    // "erased", soft keyword, experimental
    "export",
    "extends",
    // "extension", soft keyword, example: extension (s: String) def reformat: String = ...
    "false",
    "final",
    "finally",
    "for",
    "given",
    "if",
    "implicit",
    "import",
    // "infix", soft keyword, example: infix def add(other: Int): Int
    // "inline", soft keyword, example: inline def assert(condition: => Boolean): Unit = ...
    // "into", soft keyword, example: into trait IntoIterable[A] extends Iterable[A]
    "lazy",
    "match",
    "new",
    "null",
    "object",
    // "opaque", soft keyword, example: opaque type Logarithm = Double
    // "open", soft keyword, example: open class BaseComponent
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "this",
    "throw",
    "trait",
    // "transparent", soft keyword, example: transparent inline def choose(b: Boolean) = ...
    "true",
    "try",
    "type",
    // "using", soft keyword, example: def sort[A](list: List[A])(using ord: Ord[A]) = ...
    "val",
    "var",
    "while",
    "with",
    "yield",
  )
  val maxKeywordLength = keywords.foldLeft(0)(_ max _.length)
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
  val maxReservedLength = reserved.foldLeft(0)(_ max _.length)
}

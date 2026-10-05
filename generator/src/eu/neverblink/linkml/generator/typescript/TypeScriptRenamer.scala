package eu.neverblink.linkml.generator.typescript

import eu.neverblink.linkml.generator.jsonschema.JsonRenamer
import eu.neverblink.linkml.schemaview.{Case, ClassView, EnumView, TypeView}

/** Names in the generated TypeScript. The types describe the JSON data, so slot names and
  * permissible values are the inherited JSON ones from [[JsonRenamer]]. Only the names of the types
  * themselves are TypeScript identifiers.
  */
trait TypeScriptRenamer extends JsonRenamer {
  import TypeScriptRenamer.*

  override def className(el: ClassView): String = identifier(el.baseName)

  override def typeName(el: TypeView): String = identifier(el.baseName)

  override def enumName(el: EnumView): String = identifier(el.baseName)
}

object TypeScriptRenamer extends TypeScriptRenamer {

  /** Names that a generated type must not take: TypeScript reserved words and predefined types, and
    * the global utility types the generated code uses.
    */
  val reserved: Set[String] = Set(
    // reserved words
    "break",
    "case",
    "catch",
    "class",
    "const",
    "continue",
    "debugger",
    "default",
    "delete",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "finally",
    "for",
    "function",
    "if",
    "import",
    "in",
    "instanceof",
    "new",
    "null",
    "return",
    "super",
    "switch",
    "this",
    "throw",
    "true",
    "try",
    "typeof",
    "var",
    "void",
    "while",
    "with",
    // strict mode reserved words
    "implements",
    "interface",
    "let",
    "package",
    "private",
    "protected",
    "public",
    "static",
    "yield",
    // predefined types
    "any",
    "bigint",
    "boolean",
    "never",
    "number",
    "object",
    "string",
    "symbol",
    "undefined",
    "unknown",
    // global types used in the generated code
    "Omit",
    "Partial",
    "Pick",
    "Record",
  )

  /** Turn a base name into a `PascalCase` TypeScript identifier. A leading digit or a reserved name
    * gets an underscore in front.
    */
  def identifier(baseName: String): String = {
    val pascal = Case.baseToPascal(baseName)
    val name =
      pascal.map(c => if Character.isLetterOrDigit(c) || c == '_' || c == '$' then c else '_')
    if name.isEmpty then "__"
    else if Case.isNumeric(name.head) || reserved.contains(name) then "_".concat(name)
    else name
  }
}

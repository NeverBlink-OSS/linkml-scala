package eu.neverblink.linkml.generator.pydantic

import eu.neverblink.linkml.generator.jsonschema.JsonRenamer
import eu.neverblink.linkml.schemaview.{Case, ClassView, EnumView, TypeView}

/** Names in the generated Python. The classes describe the JSON data, so slot names and permissible
  * values are the inherited JSON ones from [[JsonRenamer]]. Only the names of the classes and enums
  * themselves are Python identifiers. The Python attribute names of fields and enum members are
  * made by [[PydanticRenamer.attribute]] and [[PydanticRenamer.member]].
  */
trait PydanticRenamer extends JsonRenamer {
  import PydanticRenamer.*

  override def className(el: ClassView): String = identifier(el.baseName)

  override def typeName(el: TypeView): String = identifier(el.baseName)

  override def enumName(el: EnumView): String = identifier(el.baseName)
}

object PydanticRenamer extends PydanticRenamer {

  /** Python keywords. */
  val keywords: Set[String] = Set(
    "False",
    "None",
    "True",
    "and",
    "as",
    "assert",
    "async",
    "await",
    "break",
    "class",
    "continue",
    "def",
    "del",
    "elif",
    "else",
    "except",
    "finally",
    "for",
    "from",
    "global",
    "if",
    "import",
    "in",
    "is",
    "lambda",
    "nonlocal",
    "not",
    "or",
    "pass",
    "raise",
    "return",
    "try",
    "while",
    "with",
    "yield",
  )

  /** Names that the generated code uses at module level: imports, builtins and its own helpers. */
  val moduleNames: Set[String] = Set(
    // builtins
    "bool",
    "dict",
    "float",
    "int",
    "isinstance",
    "list",
    "str",
    "ValueError",
    // imports
    "annotations",
    "re",
    "date",
    "datetime",
    "time",
    "Decimal",
    "Enum",
    "Annotated",
    "Any",
    "Literal",
    "Union",
    "BaseModel",
    "BeforeValidator",
    "ConfigDict",
    "Field",
    "PlainSerializer",
    "RootModel",
    "StrictBool",
    "StrictFloat",
    "StrictInt",
    "WrapSerializer",
    "model_serializer",
    // helpers in the generated code
    "LinkMLModel",
    "JsonDate",
    "JsonDateTime",
    "JsonTime",
    "JsonDecimal",
  )

  /** Attributes of pydantic's `BaseModel` that a field must not hide. */
  private val modelAttributes: Set[String] = Set(
    "construct",
    "copy",
    "dict",
    "from_orm",
    "json",
    "model_computed_fields",
    "model_config",
    "model_construct",
    "model_copy",
    "model_dump",
    "model_dump_json",
    "model_extra",
    "model_fields",
    "model_fields_set",
    "model_json_schema",
    "model_parametrized_name",
    "model_post_init",
    "model_rebuild",
    "model_validate",
    "model_validate_json",
    "model_validate_strings",
    "parse_file",
    "parse_obj",
    "parse_raw",
    "schema",
    "schema_json",
    "update_forward_refs",
    "validate",
  )

  /** Turn a base name into a `PascalCase` Python identifier. A leading digit or a reserved name
    * gets an underscore in front.
    */
  def identifier(baseName: String): String = {
    val name = ascii(Case.baseToPascal(baseName))
    if name.isEmpty then "__"
    else if Case.isNumeric(name.head) || keywords(name) || moduleNames(name) then "_".concat(name)
    else name
  }

  /** The Python name of a field with the given JSON key: the key itself if it is a usable
    * identifier. Otherwise other characters become `_`, leading underscores are removed (pydantic
    * keeps those names private), a leading digit gets `field_` in front, and a reserved name gets
    * `_` at the end.
    *
    * @param taken
    *   Other names the field must not take, such as those of the generated classes.
    */
  def attribute(key: String, taken: String => Boolean): String = {
    val clean = ascii(key).dropWhile(_ == '_')
    val name =
      if clean.isEmpty then "field"
      else if Case.isNumeric(clean.head) then "field_".concat(clean)
      else clean
    if keywords(name) || moduleNames(name) || modelAttributes(name) || taken(name) then
      name.concat("_")
    else name
  }

  /** The Python name of an enum member with the given value: as for [[attribute]], but a name that
    * does not start with a letter gets `v` in front.
    */
  def member(value: String): String = {
    val clean = ascii(value)
    val name =
      if clean.isEmpty then "v"
      else if Case.isAlphaUpper(clean.head) || Case.isAlphaLower(clean.head) then clean
      else "v".concat(clean)
    // Enum reserves `mro`
    if keywords(name) || name == "mro" then name.concat("_") else name
  }

  private def ascii(text: String): String = text.map(c => if Case.isStandard(c) then c else '_')
}

package eu.neverblink.linkml.generator.javagen

import eu.neverblink.linkml.generator.jsonschema.JsonRenamer
import eu.neverblink.linkml.schemaview.{
  Case,
  ClassView,
  EnumView,
  FunctionView,
  SchemaView,
  TypeView,
}

/** Names in the generated Java. Slot names and permissible values stay the JSON ones from
  * [[JsonRenamer]], since the classes describe the JSON data. [[JavaRenamer.field]] and
  * [[JavaRenamer.constant]] give the Java names of fields and enum constants.
  */
trait JavaRenamer extends JsonRenamer {
  import JavaRenamer.*

  override def className(el: ClassView): String = identifier(el.baseName)

  override def typeName(el: TypeView): String = identifier(el.baseName)

  override def enumName(el: EnumView): String = identifier(el.baseName)

  override def functionName(el: FunctionView): String = member(Case.baseToCamel(el.baseName))

  /** The name of the interface that contains the functions of a schema, such as `SearchFunctions`
    * for a schema named `search`.
    */
  def functionsInterfaceName(sv: SchemaView): String =
    unreserved(identifier(Case.base(sv.root.name)).concat("Functions"))

  /** The name of the exception class for an error class. */
  def errorName(el: ClassView): String = unreserved(className(el).concat("Error"))
}

object JavaRenamer extends JavaRenamer {

  /** Java keywords and literals, which can't be identifiers. */
  val keywords: Set[String] = Set(
    "_",
    "abstract",
    "assert",
    "boolean",
    "break",
    "byte",
    "case",
    "catch",
    "char",
    "class",
    "const",
    "continue",
    "default",
    "do",
    "double",
    "else",
    "enum",
    "extends",
    "false",
    "final",
    "finally",
    "float",
    "for",
    "goto",
    "if",
    "implements",
    "import",
    "instanceof",
    "int",
    "interface",
    "long",
    "native",
    "new",
    "null",
    "package",
    "private",
    "protected",
    "public",
    "return",
    "short",
    "static",
    "strictfp",
    "super",
    "switch",
    "synchronized",
    "this",
    "throw",
    "throws",
    "transient",
    "true",
    "try",
    "void",
    "volatile",
    "while",
  )

  /** Type names that the generated files use without a package: `java.lang`, the imports and the
    * helper class. A class or enum of the schema with one of these names would hide it.
    */
  val typeNames: Set[String] = Set(
    // java.lang
    "Boolean",
    "Byte",
    "Character",
    "Class",
    "Deprecated",
    "Double",
    "Enum",
    "Exception",
    "Float",
    "IllegalArgumentException",
    "Integer",
    "Iterable",
    "Long",
    "Math",
    "Number",
    "Object",
    "Override",
    "Record",
    "Runnable",
    "RuntimeException",
    "SafeVarargs",
    "Short",
    "String",
    "StringBuilder",
    "SuppressWarnings",
    "System",
    "Throwable",
    "Void",
    // imports
    "BigDecimal",
    "BigInteger",
    "BiConsumer",
    "DateTimeFormatter",
    "DateTimeParseException",
    "ArrayList",
    "Function",
    "LinkedHashMap",
    "List",
    "LocalDate",
    "Map",
    "Objects",
    "OffsetDateTime",
    "OffsetTime",
    "Pattern",
    "Supplier",
    "JsonAnyGetter",
    "JsonAnySetter",
    "JsonAutoDetect",
    "JsonCreator",
    "JsonGetter",
    "JsonIgnoreProperties",
    "JsonInclude",
    "JsonProperty",
    "JsonPropertyOrder",
    "JsonSetter",
    "JsonSubTypes",
    "JsonTypeInfo",
    "JsonValue",
    "Visibility",
    // the helper class in the generated package
    "LinkMLJson",
  )

  /** Names of fields and methods that the generated classes declare themselves. */
  val memberNames: Set[String] = Set("extraProperties")

  /** Turn a base name into a `PascalCase` Java identifier. A leading digit gets an underscore in
    * front, and a name that the generated files already use gets one at the end.
    */
  def identifier(baseName: String): String = {
    val name = Case.baseToPascal(baseName)
    if name.isEmpty then "__"
    else if Case.isNumeric(name.head) then "_".concat(name)
    else unreserved(name)
  }

  private lazy val lowerTypeNames = typeNames.map(_.toLowerCase)

  /** A type name that the generated files already use gets an underscore at the end. Names are
    * compared ignoring case, as macOS and Windows compare file names.
    */
  def unreserved(name: String): String =
    if lowerTypeNames(name.toLowerCase) then name.concat("_") else name

  /** The Java name of a field with the given JSON key: the key in `camelCase`. A leading digit gets
    * an underscore in front, and a keyword or a name that the classes already use gets one at the
    * end.
    */
  def field(key: String): String = member(Case.baseToCamel(Case.base(key)))

  /** A `camelCase` name, made usable as the name of a field, parameter or method. */
  def member(name: String): String =
    if name.isEmpty then "value"
    else if Case.isNumeric(name.head) then "_".concat(name)
    else if keywords(name) || memberNames(name) then name.concat("_")
    else name

  def accessor(prefix: String, field: String): String =
    prefix.concat(field.head.toUpper.toString).concat(field.tail)

  /** The Java name of an enum constant with the given value: the value in `SCREAMING_SNAKE_CASE`,
    * with an underscore in front of a leading digit.
    */
  def constant(value: String): String = {
    val name = Case.baseToScreamingSnake(Case.base(value))
    if name.isEmpty then "EMPTY"
    else if Case.isNumeric(name.head) then "_".concat(name)
    else name
  }
}

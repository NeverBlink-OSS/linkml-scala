package eu.neverblink.linkml.generator.javagen

import eu.neverblink.linkml.schemaview.{ClassView, LinkmlPattern}

/** A JSON value that the generated code can read itself.
  *
  * @param read
  *   The method of the helper class that reads it
  * @param expected
  *   What the value must be, for error messages
  * @param textual
  *   Whether it is a date or time, written as text in JSON
  */
enum Scalar(val java: String, val read: String, val expected: String, val textual: Boolean) {
  case Str extends Scalar("String", "string", "a string", false)
  case Int extends Scalar("Long", "integer", "an integer", false)
  case Num extends Scalar("Double", "number", "a number", false)
  case Bool extends Scalar("Boolean", "bool", "a boolean", false)
  case Date extends Scalar("LocalDate", "date", "a date", true)
  case DateTime extends Scalar("OffsetDateTime", "dateTime", "a date and time", true)
  case Time extends Scalar("OffsetTime", "time", "a time", true)
  case Text extends Scalar("Object", "text", "a string or an object of strings by language", false)
}

/** How the generated code reads a value: with a method of the helper class, or for a union, with
  * the reader of each member in turn.
  */
enum Reader {

  case Of(scalar: Scalar)

  /** An enum, read with its `fromValue`. */
  case Enum(name: String)

  /** The first member that accepts the value. Dates and times stay text, so that they are written
    * back unchanged.
    */
  case AnyOf(members: Seq[Reader])

  def expected: String = this match {
    case Of(s) => s.expected
    case Enum(name) => s"a value of $name"
    case AnyOf(members) => members.map(_.expected).mkString(" or ")
  }
}

/** The Java type of a field, and how its JSON is read and written. */
enum FieldType {

  /** A value that Jackson reads and writes as is: a class, an enum, a decimal or `Object`. */
  case Native(java: String)

  case NativeList(item: String)

  /** A value, or a list of them, that the generated code reads itself in strict mode. */
  case Strict(java: String, reader: Reader, multivalued: Boolean)

  /** A dict of objects by key, read and written in the dict's JSON form.
    *
    * @param keyField
    *   The slot of the objects that holds the key
    * @param construct
    *   Whether a null entry can become a new object with just the key. Not for an abstract class.
    * @param bare
    *   Whether an entry can be written as just the value of one slot, as a simple dict allows
    */
  case Dict(
      valueClass: String,
      keyField: JavaField,
      keyScalar: Scalar,
      construct: Boolean,
      bare: Boolean,
  )

  def javaType: String = this match {
    case Native(java) => java
    case NativeList(item) => s"List<$item>"
    case Strict(java, _, multivalued) => if multivalued then s"List<$java>" else java
    case Dict(valueClass, _, _, _, _) => s"Map<String, $valueClass>"
  }
}

/** The constraints of the schema on the value of a field. Bounds are decimal numbers as text. */
final case class Checks(
    required: Boolean = false,
    minLength: Option[Int] = None,
    maxLength: Option[Int] = None,
    pattern: Option[LinkmlPattern] = None,
    minValue: Option[String] = None,
    maxValue: Option[String] = None,
    minCount: Option[Int] = None,
    maxCount: Option[Int] = None,
)

/** A field of a generated class.
  *
  * @param key
  *   The slot's key in the JSON data
  * @param tpe
  *   The type that the declaring class gives the field
  * @param read
  *   The type to read the JSON value as. A subclass that narrows the range to a subclass reads the
  *   narrower type. Otherwise the same as [[tpe]].
  * @param doc
  *   The lines of the getter's doc comment
  */
final case class JavaField(
    key: String,
    name: String,
    tpe: FieldType,
    read: FieldType,
    checks: Checks,
    doc: Seq[String],
) {
  def getter: String = JavaRenamer.accessor("get", name)
  def setter: String = JavaRenamer.accessor("set", name)
}

/** A generated class.
  *
  * @param parent
  *   The Java class it extends: the `is_a` parent
  * @param fields
  *   The fields it declares itself, without the inherited ones
  * @param allFields
  *   All its fields, the inherited ones included, in the order of the JSON data
  * @param isAbstract
  *   Only a designated union is abstract, as Jackson picks a subclass for it
  * @param open
  *   Whether instances may have properties beyond the slots
  * @param parentOpen
  *   The parent's [[open]], if there is a parent
  * @param designator
  *   The field holding the type designator, and the value that marks instances of this class
  * @param union
  *   The JSON key of the designator, and the classes that a value of this class can be, with the
  *   designator values of each
  * @param bare
  *   The key and value fields, if instances can be written as just their value in a simple dict
  */
final case class JavaClass(
    view: ClassView,
    name: String,
    parent: Option[String],
    fields: Seq[JavaField],
    allFields: Seq[JavaField],
    isAbstract: Boolean,
    open: Boolean,
    parentOpen: Option[Boolean],
    designator: Option[(JavaField, String)],
    union: Option[(String, Seq[(String, Seq[String])])],
    bare: Option[(JavaField, JavaField)],
    doc: Seq[String],
)

/** A class for a whole JSON document whose tree root is not a single object.
  *
  * @param root
  *   The type of the document's content: a list or dict of the tree root class
  */
final case class JavaDocument(name: String, root: FieldType, doc: Seq[String])

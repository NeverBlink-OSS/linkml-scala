package eu.neverblink.linkml.schemaview.expression
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.schemaview.*

/** LinkML constructor expression handling, used in, e.g., `ifabsent`.
  *
  * Examples:
  *   - `ifabsent: int(42)`
  *   - `ifabsent: string("default")`
  *   - `ifabsent: "true"`
  *   - `ifabsent: SomeEnum(ENUM_VALUE)`
  *
  * Note: ifabsent is under-specified, so this is a partial "best guess" implementation. See:
  * https://github.com/linkml/linkml/issues/3834
  */
object ConstructorExpression:
  /** Exception thrown when parsing a constructor expression fails.
    * @param message
    *   The error message.
    */
  // TODO LNK-63: Make this a SchemaValidator check instead if possible
  final class EvaluationException(message: String) extends RuntimeException(message)

  private val enumExprPattern = """^([a-zA-Z_][a-zA-Z0-9_]*)\((.*)\)$""".r

  def evaluateEnum(expr: String, range: EnumView): PermissibleValue =
    val (enumName, valueName) = enumExprPattern.findFirstMatchIn(expr) match {
      case Some(v) => (v.group(1), v.group(2))
      case None => throw EvaluationException(s"Invalid enum constructor expression: '$expr'")
    }
    if (enumName != range.name)
      throw EvaluationException(
        s"Enum constructor expression '$expr' does not match the expected enum type '${range.name}'",
      )
    range._enum.permissibleValues.getOrElse(
      valueName,
      throw EvaluationException(
        s"Value '$valueName' not found in enum '${range.name}'",
      ),
    )

  /** A parsed `string(...)` expression.
    * @param quoted
    *   false for a value without quotes, such as `string(en)`. It is accepted, as in LinkML-Python,
    *   but [[eu.neverblink.linkml.schemaview.SchemaValidator]] warns about it.
    */
  final case class StringConstructor(value: String, quoted: Boolean)

  private val stringExprPattern = """^string\((.*)\)$""".r
  private val intExprPattern = """^int\(([-+]?[0-9]+)\)$""".r

  /** Keywords from the metamodel's description of `ifabsent` that aren't supported yet. Defaults
    * written with them are ignored.
    */
  private val unsupportedKeywords =
    Set(
      "bnode",
      "class_curie",
      "class_uri",
      "default_ns",
      "default_range",
      "slot_curie",
      "slot_uri",
    )

  def isUnsupportedKeyword(expr: String): Boolean = unsupportedKeywords.contains(expr.trim)

  /** Parse a `string(...)` expression. The value may be in double or single quotes, which are
    * removed. There are no escape sequences.
    *
    * @return
    *   None if the expression isn't a `string(...)` expression
    */
  def parseString(expr: String): Option[StringConstructor] =
    stringExprPattern.findFirstMatchIn(expr).map { m =>
      val inner = m.group(1)
      val quoted = inner.length >= 2 && (inner.head == '"' || inner.head == '\'') &&
        inner.last == inner.head
      if quoted then StringConstructor(inner.substring(1, inner.length - 1), quoted = true)
      else StringConstructor(inner, quoted = false)
    }

  def evaluateString(expr: String): String =
    parseString(expr).getOrElse(
      throw EvaluationException(
        s"Invalid string constructor expression: '$expr'. Expected, e.g., 'string(\"value\")'",
      ),
    ).value

  def evaluateInt(expr: String): Int =
    intExprPattern.findFirstMatchIn(expr) match {
      case Some(m) =>
        m.group(1).stripPrefix("+").toIntOption.getOrElse(
          throw EvaluationException(s"Integer in '$expr' is out of range"),
        )
      case None =>
        throw EvaluationException(
          s"Invalid integer constructor expression: '$expr'. Expected, e.g., 'int(42)'",
        )
    }

  /** Evaluate a boolean default: `true` or `false`. */
  def evaluateBoolean(expr: String): Boolean =
    expr.trim.toLowerCase match {
      case "true" => true
      case "false" => false
      case _ =>
        throw EvaluationException(
          s"Invalid boolean expression: '$expr'. Expected 'true' or 'false'",
        )
    }

  // TODO LNK-63: implement the remaining range types

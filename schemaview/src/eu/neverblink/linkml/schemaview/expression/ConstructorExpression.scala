package eu.neverblink.linkml.schemaview.expression
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.schemaview.*

/** LinkML constructor expression handling, used in, e.g., `ifabsent`.
  *
  * Examples:
  *   - `ifabsent: int(42)`
  *   - `ifabsent: string("default")`
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
  final class EvaluationException(message: String) extends RuntimeException(message)

  private val enumExprPattern = """^([a-zA-Z_][a-zA-Z0-9_]*)\((.*)\)$""".r

  def evaluateEnum(expr: String, range: EnumView): PermissibleValueImpl =
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

  // TODO LNK-63: implement the remaining range types

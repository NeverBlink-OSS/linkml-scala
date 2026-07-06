package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.{ClassDefinition, SlotDefinition}
import eu.neverblink.linkml.runtime.NcName

import scala.util.Failure

/** ADT representing something wrong with the schema, with severity levels, as well as short and
  * long descriptions of the problem
  */
sealed trait SchemaProblem:
  /** Short description of the problem
    */
  def description: String

  /** Longer description of the problem, including hints to fix if applicable
    */
  def verbose: String

  /** The severity of the problem, used to pretty print a collection of problem
    */
  def level: String

object SchemaProblem {

  /** Format a collection of problems into a text representation
    *
    * @param problems
    *   Problems to format
    * @param maxProblems
    *   Maximum number of problems to include in the text before ellipsis
    * @param verbose
    *   Whether to use the verbose description
    * @param showLevel
    *   Whether to format the severity of the problem
    * @return
    */
  def format(
      problems: Seq[SchemaProblem],
      maxProblems: Int,
      verbose: Boolean,
      showLevel: Boolean,
  ): String = {
    val limited = problems.take(maxProblems)
    val stringified = limited.map(x =>
      (if showLevel then x.level + ": " else "") + (if verbose then x.verbose else x.description),
    )
    val printed = stringified.mkString("\n")
    val restCount = problems.size - maxProblems
    val rest = if restCount > 0 then s"\nand $restCount more problems..." else ""
    printed + rest
  }

  /** Exception which formats a collection of schema (fatal) errors
    *
    * @param problems
    *   Problems to show in the exception
    * @param maxProblems
    *   Max number of problems to format
    */
  final case class ValidationFailedException(
      problems: Seq[SchemaProblem.Error | SchemaProblem.Fatal],
      maxProblems: Int,
  ) extends Exception("Schema validation failed:\n" + format(problems, maxProblems, false, false))

  /** Create a [[Failure]] containing a [[ValidationFailedException]]
    * @param problems
    *   Problems to include in the exception. Must be non-empty
    * @param maxProblems
    *   Max number of problems to include before ellipsis
    */
  def failure(
      problems: Seq[SchemaProblem.Error | SchemaProblem.Fatal],
      maxProblems: Int,
  ): Failure[Nothing] = {
    assume(problems.nonEmpty)
    Failure(ValidationFailedException(problems, maxProblems))
  }

  /** Schema is valid, but does not meet expected standards and may produce unexpected behavior
    */
  sealed trait Warning extends SchemaProblem:
    override final def level: String = "Warning"

  /** Schema has an error - Generators may throw an exception for this schema, but schema derivation
    * and validation can continue
    */
  sealed trait Error extends SchemaProblem:
    override final def level: String = "Error"

  /** Schema has a serious problem - schema derivation procedure can't be safely executed and
    * validation can't continue as a result
    */
  sealed trait Fatal extends SchemaProblem:
    override final def level: String = "Fatal"

  final case class InvalidSlotUsage(inClass: ClassDefinition, slotNames: Seq[String])
      extends Warning:
    private lazy val slotsFormatted = slotNames.mkString("'", "', '", "'")
    lazy val description: String =
      s"Invalid 'slot_usage' slots: $slotsFormatted in class ${inClass.name}"
    lazy val verbose: String =
      s"Class '${inClass.name}' has declared 'slot_usage' for slots that are not defined for its ancestors. " +
        s"These slots will not be included: $slotsFormatted"

  final case class MultipleKeyOrIdSlots(inClass: ClassDefinition, slots: Seq[SlotDefinition])
      extends Error:
    private lazy val slotsFormatted = slots.map(_.name).mkString("'", "', '", "'")
    lazy val verbose: String =
      s"Multiple key / identifier slots in class '${inClass.name}': $slotsFormatted"
    lazy val description: String = verbose

  final case class InvalidKeyOrIdSlotType(inClass: ClassDefinition, elemName: String) extends Error:
    lazy val verbose: String =
      s"Invalid type of key / identifier slot in class '${inClass.name}': '$elemName'. " +
        "Expected a basic, scalar data type (e.g., string, integer, float, uri)."
    lazy val description: String = verbose

  final case class NonUniqueName(name: String, forElements: String) extends Error:
    lazy val verbose: String = s"Non-unique name '$name' used for $forElements"

    lazy val description: String = verbose

  final case class MultipleTreeRoots(treeRoots: Seq[ClassDefinition]) extends Error:
    private lazy val classesFormatted = treeRoots.map(_.name).mkString("'", "', '", "'")
    lazy val verbose: String =
      s"Multiple classes are defined as a 'tree_root': $classesFormatted"
    lazy val description: String = verbose

  case object NoTreeRootClass extends Warning:
    lazy val verbose: String = "No 'tree_root' class is defined in the schema"
    lazy val description: String = verbose

  case object UndefinedDefaultRange extends Warning:
    lazy val description: String = "No 'default_range' is defined in the schema"
    lazy val verbose: String =
      "The 'default_range' of the schema is not defined and could not find a 'string' type to use as a fallback. " +
        "This will become a fatal error if any slots in the schema omit their 'range'. " +
        "Add a 'default_range' to the schema, import 'linkml:types', or define a 'string' type to fix."

  final case class UnknownReferenceProblem(ref: UnknownReference) extends Fatal:
    private lazy val hint: String =
      if ref.referenceValue == "string" then " Make sure you have 'linkml:types' imported." else ""
    lazy val description: String = s"Unknown reference '${ref.referenceValue}' at ${ref.path}"
    lazy val verbose: String =
      s"$description.$hint"

  final case class InvalidRangeProblem(range: InvalidRange) extends Fatal:
    lazy val description: String = s"Invalid range '${range.value}' at ${range.path}"
    lazy val verbose: String =
      s"$description, which refers to ${range.actualType}. Ranges can only reference types, classes or enums."

  final case class InvalidDefaultRangeProblem(range: InvalidDefaultRange) extends Fatal:
    lazy val description: String = s"Undefined range at ${range.path}"
    lazy val verbose: String = s"$description, " +
      "schema 'default_range' is undefined, and the fallback 'string' type is not available. " +
      "Define the 'range' of the slot, add a 'default_range' to the schema, " +
      "import 'linkml:types', or define a 'string' type to fix."

  final case class UndefinedPrefix(prefix: NcName, position: String) extends Error:
    lazy val description: String = s"Undefined prefix $prefix at $position"
    lazy val verbose: String = description
}

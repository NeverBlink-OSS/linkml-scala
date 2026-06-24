package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.Reference

import java.util
import scala.util.{Success, Try}

/** Performs validation for a [[SchemaView]], most importantly checking whether all references are
  * correct.
  */
final class SchemaValidator(using sv: SchemaView) {
  import SchemaValidator.macroValidator

  // TODO: warn about shadowing

  /** Whether omitting `range` will result in a valid reference */
  private lazy val isDefaultRangeAllowed: Boolean = sv.root.defaultRange match {
    case Some(value) => true
    case None =>
      sv.types.get("string") match {
        case Some(value) => true
        case None => false
      }
  }

  private given ValidatorContext = ValidatorContext(isDefaultRangeAllowed)

  /** Macro validator's result */
  private lazy val macroResult = macroValidator.validate(sv.root.asInstanceOf).prependedPath("/")

  /** Any invalid references present in the schema. Empty if all references are valid. */
  lazy val unknownReferences: Seq[SchemaProblem.Fatal] = {
    macroResult.unknownReferences.map(SchemaProblem.UnknownReferenceProblem(_))
  }

  /** Any usages of an undefined `default_range`. Empty if no usages found. */
  lazy val usedUndefinedDefaultRange: Seq[SchemaProblem.Fatal] = {
    macroResult.invalidDefaultRanges.map(SchemaProblem.InvalidDefaultRangeProblem(_))
  }

  /** Warning if defining a slot without a `range` will cause a fatal error, None otherwise
    */
  private lazy val undefinedDefaultRange: Option[SchemaProblem.Warning] = {
    if isDefaultRangeAllowed then None
    else Some(SchemaProblem.UndefinedDefaultRange)
  }

  /** Any `range` slots pointing at invalid elements in the schema. */
  lazy val invalidRangeTypes: Seq[SchemaProblem.Fatal] = {
    macroResult.invalidRanges.map(SchemaProblem.InvalidRangeProblem(_))
  }

  /** Error when a schema has multiple `tree_root` classes, None otherwise */
  private lazy val multipleTreeRoots: Option[SchemaProblem.Error] = {
    // Python implementation only looks at the root schema, not the imports:
    // tree_roots = [c for c in schema_view.all_classes(imports=False).values() if c.tree_root]
    // if len(tree_roots) > 0: # -> validation error
    val treeRoots = sv.root.classes.values.filter(_.treeRoot).toSeq
    if treeRoots.size > 1 then Some(SchemaProblem.MultipleTreeRoots(treeRoots))
    else None
  }

  /** Warning when there does not exist a `tree_root` class, None otherwise */
  private lazy val noTreeRoot: Option[SchemaProblem.Warning] = {
    val treeRoots = sv.root.classes.values.filter(_.treeRoot)
    if treeRoots.isEmpty then Some(SchemaProblem.NoTreeRootClass)
    else None

  }

  /** Errors for each class with multiple identifier/key slots, empty if all classes have correct
    * identifier/key slots
    */
  private lazy val identifierAndKey: Seq[SchemaProblem.Error] = {
    val errors = Seq.newBuilder[SchemaProblem.Error]
    sv.classes.values.foreach { derivedCls =>
      val keyOrId = derivedCls.derivedAttributes.values
        .collect { case s if s.slot.identifier || s.slot.key => s.slot }
      if (keyOrId.size > 1) {
        errors.addOne(SchemaProblem.MultipleKeyOrIdSlots(derivedCls.cls, keyOrId.toSeq))
      } else if (keyOrId.size == 1) {
        keyOrId.head.range.flatMap(sv.resolve).orElse(sv.schemas.collectFirst {
          case s if s.defaultRange.isDefined => s.defaultRange.flatMap(sv.resolve)
        }.flatten).orNull match {
          case _: TypeDefinition | null =>
          case elem =>
            errors.addOne(SchemaProblem.InvalidKeyOrIdSlotType(derivedCls.cls, elem.name))
        }
      }
    }
    errors.result()
  }

  /** Errors for classes, types, and enums that have non-unique names
    */
  private lazy val nonUniqueNames: Seq[SchemaProblem.Error] = {
    val errors = Seq.newBuilder[SchemaProblem.Error]
    val enumNames =
      new util.HashMap[String, String](sv.schemas.foldLeft(0)(_ + _.enums.size) << 1, 0.5f)
    sv.schemas.foreach(s =>
      s.enums.foreach { (enumName, _) =>
        val enumSchemaName = enumNames.put(enumName, s.name)
        if (enumSchemaName ne null) {
          errors.addOne(
            SchemaProblem.NonUniqueName(
              enumName,
              s"enum from '$enumSchemaName' and '${s.name}' schemas",
            ),
          )
        }
      },
    )
    val typeNames =
      new util.HashMap[String, String](sv.schemas.foldLeft(0)(_ + _.types.size) << 1, 0.5f)
    sv.schemas.foreach(s =>
      s.types.foreach { (typeName, _) =>
        val typeSchemaName = typeNames.put(typeName, s.name)
        val enumSchemaName = enumNames.get(typeName)
        if (enumSchemaName ne null) {
          errors.addOne(
            SchemaProblem.NonUniqueName(
              typeName,
              if (typeSchemaName ne null) {
                s"type from '$typeSchemaName' and '${s.name}' schemas, and enum from '$enumSchemaName' schema"
              } else {
                s"enum from '$enumSchemaName' schema and type from '${s.name}' schema"
              },
            ),
          )
        } else if (typeSchemaName ne null) {
          errors.addOne(
            SchemaProblem.NonUniqueName(
              typeName,
              s"type from '$typeSchemaName' and '${s.name}' schemas",
            ),
          )
        }
      },
    )
    val classNames =
      new util.HashMap[String, String](sv.schemas.foldLeft(0)(_ + _.classes.size) << 1, 0.5f)
    sv.schemas.foreach(s =>
      s.classes.foreach { (className, _) =>
        val classSchemaName = classNames.put(className, s.name)
        val typeSchemaName = typeNames.get(className)
        val enumSchemaName = enumNames.get(className)
        if (enumSchemaName ne null) {
          errors.addOne(
            SchemaProblem.NonUniqueName(
              className, {
                if (typeSchemaName ne null) {
                  if (classSchemaName ne null) {
                    s"class from '${s.name}' and '$classSchemaName' schemas, enum from '$enumSchemaName' schema, and type from '$typeSchemaName' schema"
                  } else {
                    s"class from '${s.name}' schema, enum from '$enumSchemaName' schema, and type from '$typeSchemaName' schema"
                  }
                } else if (classSchemaName ne null) {
                  s"class from '${s.name}' and '$classSchemaName' schemas, and enum from '$enumSchemaName' schema"
                } else {
                  s"class from '${s.name}' schema and enum from '$enumSchemaName' schema"
                }
              },
            ),
          )
        } else if (typeSchemaName ne null) {
          errors.addOne(
            SchemaProblem.NonUniqueName(
              className, {
                if (classSchemaName ne null) {
                  s"class from '${s.name}' schema, class from '$classSchemaName' schema and type from '$typeSchemaName' schema"
                } else {
                  s"class from '${s.name}' schema and type from '$typeSchemaName' schema"
                }
              },
            ),
          )
        } else if (classSchemaName ne null) {
          errors.addOne(
            SchemaProblem.NonUniqueName(
              className,
              s"class from '${s.name}' and '$classSchemaName' schemas",
            ),
          )
        }
      },
    )
    val slotNames =
      new util.HashMap[String, String](
        sv.schemas.foldLeft(0)(_ + _.slotDefinitions.size) << 1,
        0.5f,
      )
    sv.schemas.foreach(s =>
      s.slotDefinitions.foreach { (slotName, _) =>
        val slotSchemaName = slotNames.put(slotName, s.name)
        if (slotSchemaName ne null) {
          errors.addOne(
            SchemaProblem.NonUniqueName(
              slotName,
              s"slot from '${s.name}' and '$slotSchemaName' schemas",
            ),
          )
        }
      },
    )
    val subsetNames =
      new util.HashMap[String, String](sv.schemas.foldLeft(0)(_ + _.subsets.size) << 1, 0.5f)
    sv.schemas.foreach(s =>
      s.subsets.foreach { (subsetName, _) =>
        val subsetSchemaName = subsetNames.put(subsetName, s.name)
        if (subsetSchemaName ne null) {
          errors.addOne(
            SchemaProblem.NonUniqueName(
              subsetName,
              s"subset from '${s.name}' and '$subsetSchemaName' schemas",
            ),
          )
        }
      },
    )
    errors.result()
  }

  /** Ensure that any declared slot usages are refining some applicable slot (top level slot or
    * attribute)
    */
  private lazy val invalidSlotUsage: Seq[SchemaProblem.Warning] = {
    val perClass = for (cls <- sv.classes.values) yield {
      val slotUsageNames = cls.cls.slotUsage.keys
      val applicableSlotNames = cls.applicableSlots.map(_.ref.value)
      val problemSlots = slotUsageNames
        .filter(x => !applicableSlotNames.contains(x))
      if problemSlots.nonEmpty then
        Some(SchemaProblem.InvalidSlotUsage(cls.cls, problemSlots.toSeq))
      else None
    }
    perClass.flatten.toSeq
  }

  /** Any fatal problems that block further processing / validation, if any. */
  lazy val fatalProblems: Seq[SchemaProblem.Fatal] =
    unknownReferences ++
      invalidRangeTypes ++
      usedUndefinedDefaultRange

  /** Any errors found in the schema, if any. */
  private lazy val errors: Seq[SchemaProblem.Error] =
    identifierAndKey ++
      multipleTreeRoots ++
      nonUniqueNames

  /** Any warnings found in the schema, if any. */
  private lazy val warnings: Seq[SchemaProblem.Warning] =
    invalidSlotUsage ++
      undefinedDefaultRange ++
      noTreeRoot

  /** Any validation problems (fatal + error) found in the schema */
  private lazy val validationProblems: Seq[SchemaProblem.Error | SchemaProblem.Fatal] = {
    val fatal: Seq[SchemaProblem.Fatal] = fatalProblems
    if fatal.nonEmpty then fatal else errors
  }

  /** Any lint problems found in the schema (fatal + error + warning) */
  lazy val lintProblems: Seq[SchemaProblem] = {
    if fatalProblems.nonEmpty then fatalProblems
    else errors ++ warnings
  }

  /** Run the validation for the schema, formatting any errors into an exception. Ignores warnings.
    *
    * @param maxProblems
    *   Max number of problems to include in the message.
    * @return
    *   Success if no validation problems found, or an exception with an appropriate error message
    */
  def validate(maxProblems: Int = 5): Try[Unit] = {
    if validationProblems.nonEmpty then SchemaProblem.failure(validationProblems, maxProblems)
    else Success(())
  }

  /** Create a validation report of all detected [[SchemaProblem]]s, formatting problems
    * appropriately.
    *
    * @param maxProblems
    *   Max number of problems to format
    * @param verbose
    *   Whether to use the more verbose error message
    * @return
    *   None if no problems were
    */
  def lint(maxProblems: Int = 5, verbose: Boolean = false): Option[String] = {
    if lintProblems.isEmpty then None
    else
      Some(
        s"Found ${lintProblems.size} problems in the schema:\n" + SchemaProblem.format(
          lintProblems,
          maxProblems,
          verbose,
          showLevel = true,
        ),
      )
  }
}

object SchemaValidator {

  /** Macro validator instance which will be used in the [[SchemaValidator]] */
  private val macroValidator: MacroValidator[SchemaDefinitionImpl] =
    MacroValidator.derived
}

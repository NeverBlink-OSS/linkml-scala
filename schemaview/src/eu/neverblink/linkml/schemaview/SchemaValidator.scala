package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.{NcName, PrefixResolver, Reference}
import eu.neverblink.linkml.validation.*

import java.util

/** Performs validation for a [[SchemaView]], most importantly checking whether all references are
  * correct.
  */
final class SchemaValidator(using sv: SchemaView) {
  import SchemaValidator.macroValidator

  /** Location of an issue that is pinned to a JSON path within the root schema. */
  private def at(jsonPath: String): IssueLocationImpl =
    IssueLocationImpl(schemaId = Some(sv.root.id), jsonPointer = Some(jsonPath))

  /** Location of an issue that pertains to a class of the root schema. */
  private def classLocation(className: String): IssueLocationImpl =
    at(s"/classes/$className")

  /** Location of an issue that pertains to the root schema as a whole. */
  private def rootLocation: IssueLocationImpl =
    IssueLocationImpl(schemaId = Some(sv.root.id))

  // TODO: warn about shadowing

  /** Whether omitting `range` will result in a valid reference */
  private lazy val isDefaultRangeAllowed: Boolean =
    sv.root.defaultRange.isDefined || sv.types.contains("string")

  private given ValidatorContext = ValidatorContext(isDefaultRangeAllowed)

  /** Macro validator's result */
  private lazy val macroResult = sv.schemas.foldLeft(ValidatorResult.ok) { (acc, schema) =>
    // TODO LNK-166: Store the element "fromSchema"
    acc + macroValidator.validate(schema.asInstanceOf).prependedPath("/")
  }

  /** Any invalid references present in the schema. Empty if all references are valid. */
  lazy val unknownReferences: Seq[SchemaFatal] =
    macroResult.unknownReferences.map(ref =>
      // A dangling 'string' reference nearly always means 'linkml:types' was not imported, so it
      // gets its own issue type with a hint.
      if ref.referenceValue == "string" then UnknownStringReferenceImpl(location = at(ref.path))
      else
        UnknownReferenceImpl(
          location = at(ref.path),
          referenceValue = ref.referenceValue,
        ),
    )

  /** Any usages of an undefined `default_range`. Empty if no usages found. */
  lazy val usedUndefinedDefaultRange: Seq[SchemaFatal] =
    macroResult.invalidDefaultRanges.map(range =>
      InvalidDefaultRangeImpl(location = at(range.path)),
    )

  lazy val schemaIdClash: Seq[SchemaFatal] = {
    val schemas = sv.schemas.toIndexedSeq
    for
      (s1, s1index) <- schemas.zipWithIndex
      s2 <- schemas.slice(s1index + 1, schemas.size)
      if s1.id == s2.id
        // TODO LNK-154 Robust file system importing
        && s1 != s2
    yield SchemaIdClashImpl(location = IssueLocationImpl(schemaId = Some(s1.id)))
  }

  /** Warning if defining a slot without a `range` will cause a fatal error, None otherwise
    */
  private lazy val undefinedDefaultRange: Option[SchemaWarning] =
    if isDefaultRangeAllowed then None
    else Some(UndefinedDefaultRangeImpl(location = rootLocation))

  /** Any `range` slots pointing at invalid elements in the schema. */
  lazy val invalidRangeTypes: Seq[SchemaFatal] =
    macroResult.invalidRanges.map(range =>
      InvalidRangeImpl(
        location = at(range.path),
        rangeValue = range.value,
        actualType = range.actualType,
      ),
    )

  /** Error when a schema has multiple `tree_root` classes, None otherwise */
  private lazy val multipleTreeRoots: Option[SchemaError] = {
    // Python implementation only looks at the root schema, not the imports:
    // tree_roots = [c for c in schema_view.all_classes(imports=False).values() if c.tree_root]
    // if len(tree_roots) > 0: # -> validation error
    val treeRoots = sv.root.classes.values.filter(_.treeRoot).toSeq
    if treeRoots.size > 1 then
      Some(
        MultipleTreeRootsImpl(
          location = rootLocation,
          classNames = treeRoots.map(_.name),
        ),
      )
    else None
  }

  /** Warning when there does not exist a `tree_root` class, None otherwise */
  private lazy val noTreeRoot: Option[SchemaWarning] = {
    val treeRoots = sv.root.classes.values.filter(_.treeRoot)
    if treeRoots.isEmpty then Some(NoTreeRootClassImpl(location = rootLocation))
    else None

  }

  /** Errors for each class with multiple identifier/key slots, empty if all classes have correct
    * identifier/key slots
    */
  private lazy val identifierAndKey: Seq[SchemaError] = {
    val errors = Seq.newBuilder[SchemaError]
    sv.classes.values.foreach { derivedCls =>
      val keyOrId = derivedCls.derivedAttributes.values
        .collect { case s if s.slot.identifier || s.slot.key => s.slot }
      if (keyOrId.size > 1) {
        errors.addOne(
          MultipleKeyOrIdSlotsImpl(
            location = classLocation(derivedCls.cls.name),
            className = derivedCls.cls.name,
            slotNames = keyOrId.toSeq.map(_.name),
          ),
        )
      } else if (keyOrId.size == 1) {
        keyOrId.head.range.flatMap(sv.resolve).orElse(sv.schemas.collectFirst {
          case s if s.defaultRange.isDefined => s.defaultRange.flatMap(sv.resolve)
        }.flatten).orNull match {
          case _: TypeDefinition | null =>
          case elem =>
            errors.addOne(
              InvalidKeyOrIdSlotTypeImpl(
                location = classLocation(derivedCls.cls.name),
                className = derivedCls.cls.name,
                elementName = elem.name,
              ),
            )
        }
      }
    }
    errors.result()
  }

  /** Errors for classes, types, and enums that have non-unique names
    */
  private def nonUniqueName(name: String, usedFor: String): SchemaError =
    NonUniqueNameImpl(location = rootLocation, elementName = name, usedFor = usedFor)

  private lazy val nonUniqueNames: Seq[SchemaError] = {
    val errors = Seq.newBuilder[SchemaError]
    val enumNames =
      new util.HashMap[String, String](sv.schemas.foldLeft(0)(_ + _.enums.size) << 1, 0.5f)
    sv.schemas.foreach(s =>
      s.enums.foreach { (enumName, _) =>
        val enumSchemaName = enumNames.put(enumName, s.name)
        if (enumSchemaName ne null) {
          errors.addOne(
            nonUniqueName(
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
            nonUniqueName(
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
            nonUniqueName(
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
            nonUniqueName(
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
            nonUniqueName(
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
            nonUniqueName(
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
            nonUniqueName(
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
            nonUniqueName(
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
  private lazy val invalidSlotUsage: Seq[SchemaWarning] =
    sv.classes.values.foldLeft(Seq.newBuilder[SchemaWarning]) { (acc, cls) =>
      // Collect all slot names that are applicable to this class definition.
      val applicableSlotNames = new util.HashSet[String]
      cls.ancestors(true).foreach { anc =>
        val keys = anc.cls.attributes.keysIterator
        while (keys.hasNext) applicableSlotNames.add(keys.next())
        val slots = anc.cls.slots.iterator
        while (slots.hasNext) applicableSlotNames.add(slots.next().value)
      }
      // Filter problematic slots for the warning per class
      val problemSlots = cls.cls.slotUsage.keys
        .filter(x => !applicableSlotNames.contains(x))
      if (problemSlots.nonEmpty) {
        acc.addOne(
          InvalidSlotUsageImpl(
            location = classLocation(cls.cls.name),
            className = cls.cls.name,
            slotNames = problemSlots.toSeq,
          ),
        )
      }
      acc
    }.result()

  private def slotImplicitPrefix(
      slotDefinition: SlotDefinition,
      prefixResolver: PrefixResolver,
      locationPrefix: String,
  ): Option[SchemaError] = {
    slotDefinition.implicitPrefix match {
      case Some(prefix) if prefixResolver.resolvePrefix(prefix).isEmpty =>
        Some(
          undefinedPrefix(prefix, s"$locationPrefix/${slotDefinition.name}/implicit_prefix"),
        )
      case _ => None
    }
  }

  private def undefinedPrefix(prefix: NcName, position: String): SchemaError =
    UndefinedPrefixImpl(location = at(position), prefix = prefix)

  private lazy val unknownPrefixes: Seq[SchemaError] = {
    sv.root.emitPrefixes.zipWithIndex.flatMap((prefix, idx) =>
      if sv.rootPrefixResolver.resolvePrefix(prefix).isEmpty
      then Some(undefinedPrefix(prefix, s"/emit_prefixes/$idx"))
      else None,
    ) ++
      sv.types.values.flatMap(tv => {
        tv._type.implicitPrefix match {
          case Some(prefix) if tv.definingPrefixResolver.resolvePrefix(prefix).isEmpty =>
            Some(undefinedPrefix(prefix, s"/types/${tv._type.name}/implicit_prefix"))
          case _ => None
        }
      }) ++
      sv.slotDefinitions.values.flatMap(slotView =>
        slotImplicitPrefix(slotView.inner, slotView.definingPrefixResolver, "/slots"),
      )
      ++
      sv.classes.values.flatMap(classView =>
        classView.cls.slotUsage.values.flatMap(
          slotImplicitPrefix(
            _,
            classView.definingPrefixResolver,
            s"/classes/${classView.cls.name}/slot_usage",
          ),
        ) ++
          classView.cls.attributes.values.flatMap(
            slotImplicitPrefix(
              _,
              classView.definingPrefixResolver,
              s"/classes/${classView.cls.name}/attributes",
            ),
          ),
      )
  }

  private lazy val invalidUris: Seq[SchemaError] = {
    sv.elements.values.flatMap { elem =>
      if elem.uriOrCurie.isValid then None
      else
        Some(
          InvalidUriOrCurieImpl(
            location = IssueLocationImpl(schemaId = Some(elem.definingSchema.id)),
            uriOrCurie = elem.uriOrCurie,
            elementType = elem.elementType,
            elementName = elem.inner.name,
          ),
        )
    }.toSeq
  }

  /** Any fatal problems that block further processing / validation, if any. */
  lazy val fatalProblems: Seq[SchemaFatal] =
    unknownReferences ++
      invalidRangeTypes ++
      usedUndefinedDefaultRange ++
      schemaIdClash

  /** Any errors found in the schema, if any. */
  private lazy val errors: Seq[SchemaError] =
    identifierAndKey ++
      multipleTreeRoots ++
      nonUniqueNames ++
      unknownPrefixes ++
      invalidUris

  /** Any warnings found in the schema, if any. */
  private lazy val warnings: Seq[SchemaWarning] =
    invalidSlotUsage ++
      undefinedDefaultRange ++
      noTreeRoot

  /** Any validation problems (fatal + error) found in the schema, empty if the schema is valid.
    * Warnings are not included - see [[lintProblems]] for those.
    *
    * As elsewhere, the issues' messages are left for the consumer to `infer()`.
    */
  lazy val validationProblems: Seq[SchemaError | SchemaFatal] = {
    val fatal: Seq[SchemaFatal] = fatalProblems
    if fatal.nonEmpty then fatal else errors
  }

  /** Any lint problems found in the schema (fatal + error + warning) */
  lazy val lintProblems: Seq[SchemaIssue] = {
    if fatalProblems.nonEmpty then fatalProblems
    else errors ++ warnings
  }

  /** Create a validation report of all detected issues, formatting problems appropriately.
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
        s"Found ${lintProblems.size} problems in the schema:\n" + SchemaIssues.format(
          lintProblems.map(_.infer()),
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

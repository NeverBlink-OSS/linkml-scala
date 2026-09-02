package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.{NcName, PrefixResolver, Reference, Uri}
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.validation.*

import java.{lang, util}

/** Performs validation for a [[SchemaView]], most importantly checking whether all references are
  * correct.
  */
final class SchemaValidator(using sv: SchemaView) {
  import SchemaValidator.macroValidator

  /** Location of an issue that is pinned to a JSON path within the root schema. */
  private def at(jsonPath: String, schemaId: Uri = sv.root.id): IssueLocationImpl =
    new IssueLocationImpl(schemaId = new Some(schemaId), jsonPointer = new Some(jsonPath))

  private def elementLocation(elementView: ElementView[?, ?]): IssueLocationImpl = {
    elementView match {
      case ClassView(cls, definingSchema) =>
        new IssueLocationImpl(
          schemaId = Some(definingSchema.id),
          jsonPointer = Some("/classes/" + cls.name),
        )
      case SlotView(slot, definingSchema) =>
        // currently not possible to differentiate between attributes and slots
        new IssueLocationImpl(schemaId = Some(definingSchema.id))
      case EnumView(_enum, definingSchema) =>
        new IssueLocationImpl(
          schemaId = Some(definingSchema.id),
          jsonPointer = Some("/enums/" + _enum.name),
        )
      case TypeView(_type, definingSchema) =>
        new IssueLocationImpl(
          schemaId = Some(definingSchema.id),
          jsonPointer = Some("/types/" + _type.name),
        )
      case SubsetView(subset, definingSchema) =>
        new IssueLocationImpl(
          schemaId = Some(definingSchema.id),
          jsonPointer = Some("/subsets/" + subset.name),
        )
    }
  }

  /** Location of an issue that pertains to the root schema as a whole. */
  private def rootLocation: IssueLocationImpl =
    new IssueLocationImpl(schemaId = new Some(sv.root.id))

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
      if ref.referenceValue == "string" then new UnknownStringReferenceImpl(location = at(ref.path))
      else
        new UnknownReferenceImpl(
          location = at(ref.path),
          referenceValue = ref.referenceValue,
        ),
    )

  /** Any usages of an undefined `default_range`. Empty if no usages found. */
  lazy val usedUndefinedDefaultRange: Seq[SchemaFatal] =
    macroResult.invalidDefaultRanges.map(range =>
      new InvalidDefaultRangeImpl(location = at(range.path)),
    )

  lazy val schemaIdClash: Seq[SchemaFatal] = {
    val clashes = Vector.newBuilder[SchemaFatal]
    val schemas = sv.schemas
    schemas.foreach {
      val seen = new util.HashMap[Uri, SchemaDefinition](schemas.size << 1, 0.5f)
      s1 =>
        val s2 = seen.putIfAbsent(s1.id, s1)
        if ((s2 ne null) && !s2.equals(s1)) { // TODO LNK-154 Robust file system importing
          clashes.addOne(
            new SchemaIdClashImpl(location = new IssueLocationImpl(schemaId = new Some(s1.id))),
          )
        }
    }
    clashes.result()
  }

  /** Warning if defining a slot without a `range` will cause a fatal error, None otherwise
    */
  private lazy val undefinedDefaultRange: Option[SchemaWarning] =
    if isDefaultRangeAllowed then None
    else new Some(new UndefinedDefaultRangeImpl(location = rootLocation))

  /** Any `range` slots pointing at invalid elements in the schema. */
  lazy val invalidRangeTypes: Seq[SchemaFatal] =
    macroResult.invalidRanges.map(range =>
      new InvalidRangeImpl(
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
    val treeRoots = sv.root.classes.values.collect { case x if x.treeRoot => x.name }.toSeq
    if treeRoots.size > 1 then
      new Some(
        new MultipleTreeRootsImpl(
          location = rootLocation,
          classNames = treeRoots,
        ),
      )
    else None
  }

  /** Warning when there does not exist a `tree_root` class, None otherwise */
  private lazy val noTreeRoot: Option[SchemaWarning] =
    if (sv.root.classes.values.exists(_.treeRoot)) None
    else new Some(new NoTreeRootClassImpl(location = rootLocation))

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
          new MultipleKeyOrIdSlotsImpl(
            location = elementLocation(derivedCls),
            className = derivedCls.cls.name,
            slotNames = keyOrId.toSeq.map(_.name),
          ),
        )
      } else if (keyOrId.size == 1) {
        keyOrId.head.range.flatMapFast(sv.resolve).orElseFast(sv.schemas.collectFirst {
          case s if s.defaultRange.isDefined => sv.resolve(s.defaultRange.get)
        }.flatten).orNullFast match {
          case _: TypeDefinition | null =>
          case elem =>
            errors.addOne(
              new InvalidKeyOrIdSlotTypeImpl(
                location = elementLocation(derivedCls),
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
  private def nonUniqueName(name: String, renamed: String, usedFor: String): SchemaError =
    new NonUniqueNameImpl(
      location = rootLocation,
      elementName = name,
      transformedName = renamed,
      usedFor = usedFor,
    )

  private lazy val nonUniqueNames: Seq[SchemaError] = {
    val errors = Seq.newBuilder[SchemaError]
    val enumNames =
      new util.HashMap[String, String](sv.schemas.foldLeft(0)(_ + _.enums.size) << 1, 0.5f)
    sv.schemas.foreach(s =>
      s.enums.foreach { (enumName, _) =>
        val renamed = Case.base(enumName)
        val enumSchemaName = enumNames.put(renamed, s.name)
        if (enumSchemaName ne null) {
          errors.addOne(
            nonUniqueName(
              enumName,
              renamed,
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
        val renamed = Case.base(typeName)
        val typeSchemaName = typeNames.put(renamed, s.name)
        val enumSchemaName = enumNames.get(renamed)
        if (enumSchemaName ne null) {
          errors.addOne(
            nonUniqueName(
              typeName,
              renamed,
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
              renamed,
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
        val renamed = Case.base(className)
        val classSchemaName = classNames.put(renamed, s.name)
        val typeSchemaName = typeNames.get(renamed)
        val enumSchemaName = enumNames.get(renamed)
        if (enumSchemaName ne null) {
          errors.addOne(
            nonUniqueName(
              className,
              renamed, {
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
              className,
              renamed, {
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
              renamed,
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
        val renamed = Case.base(slotName)
        val slotSchemaName = slotNames.put(renamed, s.name)
        if (slotSchemaName ne null) {
          errors.addOne(
            nonUniqueName(
              slotName,
              renamed,
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
        val renamed = Case.base(subsetName)
        val subsetSchemaName = subsetNames.put(renamed, s.name)
        if (subsetSchemaName ne null) {
          errors.addOne(
            nonUniqueName(
              subsetName,
              renamed,
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
      cls.ancestorsWithSelf.foreach { anc =>
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
          new InvalidSlotUsageImpl(
            location = elementLocation(cls),
            className = cls.cls.name,
            slotNames = problemSlots.toSeq,
          ),
        )
      }
      acc
    }.result()

  private def undefinedPrefix(prefix: NcName, position: String, schemaId: Uri): SchemaError =
    UndefinedPrefixImpl(location = at(position), prefix = prefix)

  private def slotImplicitPrefix(
      slotDefinition: SlotDefinition,
      prefixResolver: PrefixResolver,
      locationPrefix: String,
      schemaId: Uri,
  ): Option[SchemaError] = {
    slotDefinition.implicitPrefix match {
      case Some(prefix) if prefixResolver.resolvePrefix(prefix).isEmpty =>
        new Some(
          undefinedPrefix(
            prefix,
            s"$locationPrefix/${slotDefinition.name}/implicit_prefix",
            schemaId,
          ),
        )
      case _ => None
    }
  }

  private lazy val unknownPrefixes: Seq[SchemaError] = {
    sv.root.emitPrefixes.zipWithIndex.flatMap((prefix, idx) =>
      if sv.rootPrefixResolver.resolvePrefix(prefix).isEmpty
      then new Some(undefinedPrefix(prefix, s"/emit_prefixes/$idx", sv.root.id))
      else None,
    ) ++
      sv.types.values.flatMap(tv => {
        tv._type.implicitPrefix match {
          case Some(prefix) if tv.definingPrefixResolver.resolvePrefix(prefix).isEmpty =>
            new Some(
              undefinedPrefix(
                prefix,
                s"/types/${tv._type.name}/implicit_prefix",
                tv.definingSchema.id,
              ),
            )
          case _ => None
        }
      }) ++
      sv.slotDefinitions.values.flatMap(slotView =>
        slotImplicitPrefix(
          slotView.inner,
          slotView.definingPrefixResolver,
          "/slots",
          slotView.definingSchema.id,
        ),
      ) ++
      sv.classes.values.flatMap(classView =>
        classView.cls.slotUsage.values.flatMap(
          slotImplicitPrefix(
            _,
            classView.definingPrefixResolver,
            s"/classes/${classView.cls.name}/slot_usage",
            classView.definingSchema.id,
          ),
        ) ++
          classView.cls.attributes.values.flatMap(
            slotImplicitPrefix(
              _,
              classView.definingPrefixResolver,
              s"/classes/${classView.cls.name}/attributes",
              classView.definingSchema.id,
            ),
          ),
      )
  }

  private lazy val invalidUris: Seq[SchemaError] = {
    sv.elements.values.flatMap { elem =>
      if elem.uriOrCurie.isValid then None
      else
        new Some(
          new InvalidUriOrCurieImpl(
            location = IssueLocationImpl(schemaId = new Some(elem.definingSchema.id)),
            uriOrCurie = elem.uriOrCurie,
            elementType = elem.elementType,
            elementName = elem.inner.name,
          ),
        )
    }.toSeq
  }

  private lazy val nameChecks: Seq[SchemaIssue] = {
    val builder = Seq.newBuilder[SchemaIssue]
    sv.elements.values.foreach { el =>
      if el.name.nonEmpty then {
        lazy val location = elementLocation(el)
        if !Case.isAlphanumeric(el.name.head) || !Case.isAlphanumeric(el.name.last) then
          builder.addOne(
            FlankingSeparatorImpl(
              elementName = el.name,
              location = location,
            ),
          )
        if el.name.exists(!Case.(_)) then
          builder.addOne(
            NonAsciiNameImpl(
              elementName = el.name,
              location = location,
            ),
          )
        if el.name.exists(!Case.isAllowedAscii(_)) then
          builder.addOne(
            NonAsciiNameImpl(
              elementName = el.name,
              location = location,
            ),
          )
      }
    }
    builder.result()
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
  def lint(maxProblems: Int = 5, verbose: Boolean = false): Option[String] =
    if (lintProblems.isEmpty) None
    else {
      val formattedProblems = SchemaIssues.format(
        lintProblems.map(_.infer()),
        maxProblems,
        verbose,
        showLevel = true,
      )
      new Some(s"Found ${lintProblems.size} problems in the schema:\n$formattedProblems")
    }
}

object SchemaValidator {

  /** Macro validator instance which will be used in the [[SchemaValidator]] */
  private val macroValidator: MacroValidator[SchemaDefinitionImpl] =
    MacroValidator.derived
}

package eu.neverblink.linkml.generator.scala

import eu.neverblink.linkml.generator.util.*
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.schemaview.*
import eu.neverblink.linkml.schemaview.expression.StringInterpolationExpression
import fastparse.Parsed
import java.lang

final class ScalaGenerator(using sv: SchemaView) extends ScalaRenamer {
  import ScalaGenerator.*
  import CombineFunction.*

  /** Whether a class is generated as a single case class named after it, with no separate interface
    * and `...Impl` case class. Only concrete classes without children qualify, and only when
    * [[Options.skipLeafInterfaces]] is on.
    */
  private def isCaseClassOnly(classView: ClassView, options: Options): Boolean =
    options.skipLeafInterfaces && classView.isConcrete &&
      !classView.hasDescendants

  /** Name of the Scala class that holds the instances of a concrete LinkML class. */
  private def implName(classView: ClassView, options: Options): String = {
    val name = className(classView)
    if isCaseClassOnly(classView, options) then name else name.concat("Impl")
  }

  /** The `@typeDesignator` annotation for a class. It lists the implementations of the class, so
    * that codecs can pick one by the designator value.
    *
    * None if the class is not a
    * [[eu.neverblink.linkml.schemaview.ClassView.isTypeDesignatorUnion type designator union]].
    * Also None if the designator's range has no values that name classes, such as `integer`.
    */
  private def typeDesignatorAnnotation(classView: ClassView, options: Options): Option[String] =
    if (classView.isTypeDesignatorUnion) {
      classView.typeDesignatorView.flatMapFast { designator =>
        val members = classView.typeDesignatorMembers
        if (members.forall(_.typeDesignatorValues.nonEmpty)) {
          Some(
            members.map { member =>
              val impl = implName(member, options)
              def withValues(args: String) = s"typeDesignator.Member(classOf[$impl], $args),"
              def uri(v: String) = s"Uri(${scalaStringLiteral(v)})"
              def curie(v: String) = s"Curie(${scalaStringLiteral(v)})"
              val range = designator match {
                case tav: TypeAttributeView => tav.typeView.runtimeType
                case _ => null
              }
              val values = member.typeDesignatorValues
              val value = values.head
              range match {
                case UriType => withValues(uri(value))
                case CurieType => withValues(curie(value))
                case UriOrCurieType =>
                  // The URI and the CURIE are the same when no prefix shortens the URI.
                  if (values.sizeIs == 1) withValues(uri(value))
                  else withValues(s"${uri(value)}, ${curie(values(1))}")
                case _ => withValues(scalaStringLiteral(value)) // The LinkML class name
              }
            }.mkString(
              s"@typeDesignator(\n  ${scalaStringLiteral(designator.slotView.aliasedName)},\n  ",
              "\n  ",
              "\n)",
            ),
          )
        } else None
      }
    } else None

  /** Generate Scala counterparts of all LinkML model elements: Classes, Types and Enums.
    * @param options
    *   What to generate. See [[ScalaGenerator.Options]].
    * @return
    *   Tuples of form (file name, file content) for all elements in the LinkML model
    */
  def generate(
      options: ScalaGenerator.Options = ScalaGenerator.Options(),
  ): Iterable[(String, String)] = {
    val header = GeneratedHeader("//") + "\n"
    (generateClasses(options) ++ generateEnums(options) ++ generateTypeDefinitions(options)
      ++ (if options.generateEmitPrefixes then generateEmitPrefixes(options.`package`) else None))
      .map((file, code) => file -> header.concat(code))
  }

  /** Generate Scala counterparts of LinkML classes: case class implementations for instantiable
    * classes, abstract class interfaces for non-mixin classes, traits for mixins, with LinkML
    * inheritance modeled in abstract classes and traits. Instantiable classes without children
    * become a single case class when [[Options.skipLeafInterfaces]] is on.
    * @return
    *   Tuples of form (file name, file content) for all LinkML classes
    */
  private def generateClasses(options: Options): Iterable[(String, String)] = {
    sv.classes.values.flatMap { classView =>
      // Let's treat linkml:Any as a built-in: no type aliasing
      if classView.isAny then None
      else {
        val cls = classView.cls
        val collectionForm = CollectionForm.of(classView)
        val scalaFields = for attribute <- classView.sortedAttributeViews yield {
          makeScalaField(attribute, collectionForm, classView, options)
        }
        val caseClassOnly = isCaseClassOnly(classView, options)
        val shouldBeTrait =
          cls.mixin || classView.uriStr == "https://w3id.org/linkml/EnumExpression"
        val isSlotDefinitionClass = classView.uriStr == "https://w3id.org/linkml/SlotDefinition"
        val name = className(classView)
        // A field changed by a `slot_usage` is declared again here, with a type that may not
        // match the parent's, so the code does not compile (LNK-232).
        val interfaceFields =
          (cls.slots.map(_.value) ++ cls.attributes.keys ++ cls.slotUsage.keys).map(name =>
            scalaCamel(Case.base(name)),
          ).toSet
        Some(
          name.concat(".scala") -> ScalaClassInfo(
            name,
            options.`package`,
            scalaFields,
            (cls.isA ++ cls.mixins).map(ref => scalaPascal(ref.value)).toSeq,
            interfaceFields,
            !classView.isConcrete,
            caseClassOnly,
            shouldBeTrait,
            isSlotDefinitionClass,
            makeInferredFields(classView, options),
            // Data matching `range_expression` has to be allowed, but is not checked against it.
            classView.allowsExtraSlots,
            typeDesignatorAnnotation(classView, options),
            ScalaDoc(classView.materialize, classView.definingSchema.id, options),
          ).print,
        )
      }
    }
  }

  /** Generate Scala counterparts of regular (static) LinkML enums.
    * @return
    *   Tuples of form (file name, file content) for all LinkML classes
    */
  private def generateEnums(options: Options): Iterable[(String, String)] =
    sv.enums.values.flatMap { ev =>
      val en = ev._enum
      if (!ScalaGenerator.definesEnum(ev)) None
      else {
        val name = enumName(ev)
        val enumCases = ev.derivedValues.map(v =>
          ScalaEnumCase(
            caseName = v.aliasedName,
            objectName = permissibleValueName(ev, v.pv),
            enumName = name,
            doc = ScalaDoc(v.pv, ev.definingSchema.id, options),
          ),
        )
        val enumInfo =
          ScalaEnumInfo(
            name,
            options.`package`,
            enumCases,
            !en.`abstract`,
            en.mixin,
            ScalaDoc(en, ev.definingSchema.id, options),
          )
            .generate()
        Some(name.concat(".scala") -> enumInfo)
      }
    }

  private def generateEmitPrefixes(pkg: String): Option[(String, String)] = {
    if sv.root.emitPrefixes.isEmpty then return None

    val sb = new java.lang.StringBuilder
    sv.root.emitPrefixes.foreach { prefix =>
      val uri = sv.rootPrefixes.namespaceOrNull(prefix)
      if (uri eq null) sys.error(s"Unknown prefix to emit: $prefix")
      sb.append('"').append(prefix).append("\" -> \"").append(uri).append("\",\n")
    }
    val prefixes = sb.toString
    Some(
      "Prefixes.scala" ->
        indent"""package $pkg
           |
           |/** Prefixes emitted from the `emit_prefixes` schema slot.
           |  */
           |object Prefixes {
           |  val map: Map[String, String] = Map(
           |    $prefixes
           |  )
           |}
           |""".stripMargin,
    )
  }

  /** Generate type aliases for schema type definitions. Skips aliases for types defined in
    * linkml:types and points to the runtime classes directly.
    */
  private def generateTypeDefinitions(options: Options): Iterable[(String, String)] = {
    sv.types.values.collect {
      case tv if !tv.isPrimitive =>
        val name = typeName(tv)
        s"$name.scala" -> typeDef(
          options.`package`,
          name,
          typeToRuntime(tv),
          ScalaDoc(tv._type, tv.definingSchema.id, options),
        )
    }
  }

  /** Generate a type alias for custom types and classes with the linkml:Any class URI, aliasing
    * them to [[typeRange]].
    *
    * @param pkg
    *   Package to generate file in
    * @param typeName
    *   Name of the type/class to alias
    * @param typeRange
    *   Name of the type/class to alias to
    */
  private def typeDef(pkg: String, typeName: String, typeRange: String, doc: ScalaDoc): String = {
    s"""package $pkg
       |
       |import eu.neverblink.linkml.runtime.*
       |${doc.print}
       |type $typeName = $typeRange
       |""".stripMargin
  }

  def typeToRuntime(tv: TypeView): String =
    if tv.isUnion then
      // Duplicates collapse, as the same Scala type can stand for more than one member.
      tv.unionAlternatives.map(m => if m.isPrimitive then typeToRuntime(m) else typeName(m))
        .distinct.mkString(" | ")
    else nonUnionTypeToRuntime(tv)

  private def nonUnionTypeToRuntime(tv: TypeView): String = tv.runtimeType match {
    case StringType => "String"
    case IntegerType => "Int"
    case FloatType => "Float"
    case DoubleType => "Double"
    case BooleanType => "Boolean"
    case DecimalType => "BigDecimal"
    case AnyType => "LinkmlAny"

    case DateType => "LinkmlDate"
    case DateTimeType => "LinkmlDateTime"
    case TimeType => "LinkmlTime"

    case UriOrCurieType => "UriOrCurie"
    case UriType => "Uri"
    case CurieType => "Curie"
    case NcNameType => "NcName"
    case LocalizedTextType => "LocalizedText"
    case UnknownType => tv.derivedType.base.getOrElseFast("Unknown")
  }

  /** Translates an attribute's resolved range to the appropriate Scala type.
    *
    * @param attribute
    *   AttributeView of the slot we are generating the Scala type for
    * @return
    *   (Scala type which best represents the range, default value for the type if applicable)
    */
  private def baseRange(
      attribute: AttributeView,
      options: Options,
  ): (scalaType: String, baseDefault: Option[String]) = {
    attribute match {
      // Redirect classes with uri == linkml:Any to the runtime class, as by spec it's not a builtin class:
      // From https://linkml.io/linkml/schemas/advanced.html#linkml-any-type
      // "but any class in the schema can take on this roll be being declared as linkml:Any using class_uri"
      case AnyView(_, _) =>
        ("LinkmlAny", None)
      case ClassInlineAttributeView(_, _, classView, _) =>
        // Abstract classes and mixins have no case class, so the slot uses the interface type.
        // A type designator union also uses the interface type, so that the slot can hold any
        // implementation. A concrete class uses its case class, so a subclass can't narrow the
        // range to a subclass of it (LNK-232).
        (
          if (classView.isConcrete && !classView.isTypeDesignatorUnion) implName(classView, options)
          else className(classView),
          None,
        )
      case ClassReferenceAttributeView(_, _, classView, _) =>
        val name = className(classView)
        (s"Reference[$name]", None)
      case TypeAttributeView(slotView, _, typeView) =>
        val scalaType = if typeView.isPrimitive then typeToRuntime(typeView) else typeName(typeView)
        val default = slotView.slot.ifabsent.filter(_ => scalaType == "Boolean").mapFast {
          _.trim.toLowerCase match {
            case "true" => "true"
            case "false" => "false"
            case other =>
              throw IllegalArgumentException(
                s"Unsupported ifabsent for boolean slot ${slotView.name}: $other",
              )
          }
        }
        (scalaType, default)
      case EnumAttributeView(slotView, _, enumView) =>
        if (!ScalaGenerator.definesEnum(enumView))
          ("String", None) // fallback to String for dynamic enums
        else {
          val name = enumName(enumView)
          val default: Option[PermissibleValue] = slotView.ifAbsent(enumView)
          (name, default.mapFast(p => s"$name.${permissibleValueName(enumView, p)}"))
        }
    }
  }

  /** Create a [[TypedDefault]] containing all the type-level information for a slot's range / Scala
    * field type. Handles implicit / explicit inlines, inline styles, combining functions and
    * default values (sans `ifabasent`).
    * @param attribute
    *   Attribute to derive the type-level information for
    * @return
    *   The inferred [[TypedDefault]]
    */
  private def makeTypedDefault(attribute: AttributeView, options: Options): TypedDefault = {
    val slot = attribute.slotView
    val (scalaType, defaultValue) = baseRange(attribute, options)
    // Defaults coming from 'ifabsent' are meaningful and must survive serialization, unlike the
    // implicit "empty" defaults (None / Seq() / Map()) that codecs are free to omit.
    val ifAbsentAnnotation =
      if defaultValue.isDefined then Seq("@serializeDefault") else Seq()
    InlineType(slot) match {
      case InlineType.plain =>
        TypedDefault(scalaType, defaultValue, annotations = ifAbsentAnnotation)
      case InlineType.optional if isPlainBoolean(slot, scalaType) =>
        TypedDefault(
          scalaType,
          default = Some(defaultValue.getOrElseFast("false")),
          annotations = ifAbsentAnnotation,
          combineFunc = combineBoolean,
        )
      case InlineType.optional =>
        TypedDefault(
          s"Option[$scalaType]",
          default = Some(defaultValue.foldFast("None")(v => s"Some($v)")),
          annotations = ifAbsentAnnotation,
          combineFunc = combineOption(combineFallback),
        )
      case InlineType.list =>
        TypedDefault(
          s"Seq[$scalaType]",
          default = if slot.slot.required then None else Some("Seq()"),
          combineFunc = combineSeq,
        )
      case InlineType.dict(style) =>
        val annotation = style match {
          case _: CollectionForm.SimpleDict => "@simpleDict"
          case _: CollectionForm.CompactDict => "@compactDict"
        }
        TypedDefault(
          s"Map[String, $scalaType]",
          default = if slot.slot.required then None else Some("Map()"),
          annotations = Seq(annotation),
          combineFunc = combineMap,
        )
    }
  }

  /** Rewrite the [[TypedDefault]] of a type designator slot (`designates_type: true`). Its value is
    * fixed to the identifier of the containing class.
    *
    * @param attribute
    *   The type designator attribute
    * @param owner
    *   The class the field is generated into (not necessarily where the slot is declared)
    * @param fromRange
    *   The [[TypedDefault]] inferred from the range alone, returned unchanged when the designator
    *   gets no special handling
    * @see
    *   https://linkml.io/linkml/schemas/type-designators.html
    */
  private def designatorDefault(
      attribute: AttributeView,
      owner: ClassView,
      fromRange: TypedDefault,
  ): TypedDefault = {
    val literal = attribute match {
      case TypeAttributeView(_, _, typeView) =>
        typeView.runtimeType match {
          case StringType => Some(scalaStringLiteral(owner.cls.name))
          // A `uriorcurie` value is a URI once it is read, so it is a `Uri` too
          case UriType | UriOrCurieType => Some(s"Uri(${scalaStringLiteral(owner.uriStr)})")
          case CurieType => Some(s"Curie(${scalaStringLiteral(owner.compactUri)})")
          case _ => None
        }
      case _ => None
    }
    literal.foldFast(fromRange) { value =>
      InlineType(attribute.slotView) match {
        case InlineType.plain =>
          fromRange.copy(default = Some(value), annotations = Seq("@serializeDefault"))
        case InlineType.optional =>
          fromRange.copy(default = Some(s"Some($value)"), annotations = Seq("@serializeDefault"))
        case _ => fromRange
      }
    }
  }

  /** Remap the [[CombineFunction]] if necessary, handles special metamodel slots.
    *
    * The boolean slots (`any_of`, `all_of`, `exactly_one_of`, `none_of`) are combined in a stricter
    * way than in the LinkML specification and LinkML-Python, so that a subclass accepts only values
    * that its parent classes accept. See the combining functions in the runtime `Combining.scala`.
    *
    * @param slot
    *   Slot to remap the combining function
    * @param rangeCombineFunc
    *   The type-level inferred [[CombineFunction]]
    * @param scalaType
    *   The Scala type of the slot's values, without the collection
    * @return
    *   The remapped combine functions, or [[rangeCombineFunc]] if no change was necessary
    * @see
    *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#algorithm-combine-slots
    */
  private def remapMetamodelCombineFunctions(
      slot: SlotView,
      rangeCombineFunc: CombineFunction,
      scalaType: => String,
  ): CombineFunction =
    slot.uriStr match {
      case "https://w3id.org/linkml/maximum_value" => combineOption(combineMax)
      case "https://w3id.org/linkml/minimum_value" => combineOption(combineMin)
      case "https://w3id.org/linkml/pattern" => combineOption(combinePattern)
      case "https://w3id.org/linkml/range" => combineOption(combineRange)
      case "https://w3id.org/linkml/any_of" | "https://w3id.org/linkml/exactly_one_of" =>
        combineNonEmpty
      case "https://w3id.org/linkml/all_of" => combineAllOf(scalaType)
      case "https://w3id.org/linkml/none_of" => combineDistinct
      case _ => rangeCombineFunc
    }

  /** Test whether an attribute is a single-valued `string`. That is the only shape a slot can have
    * to *carry* an `equals_expression`, and the one substitution that needs no [[stringify]]
    * wrapper.
    */
  private def isSingleValuedString(attribute: AttributeView): Boolean =
    attribute match {
      case TypeAttributeView(slotView, _, typeView) =>
        (typeView.runtimeType eq StringType) && (InlineType(slotView) match {
          case InlineType.plain | InlineType.optional => true
          case _ => false
        })
      case _ => false
    }

  /** Describe why `attribute` cannot be substituted into an expression, or None if it can.
    */
  private def substitutionProblem(attribute: AttributeView): Option[String] =
    attribute match {
      case _: ClassInlineAttributeView =>
        Some("is an inlined class, which has no string representation")
      case _ => None
    }

  /** Whether a slot's Scala field is wrapped in `Option`, and so has to be unwrapped before its
    * value can be read. Optional booleans are the exception: they are emitted as a plain `Boolean`
    * defaulting to `false`. Mirrors [[makeTypedDefault]].
    */
  private def isOptionField(attribute: AttributeView, options: Options): Boolean = {
    val slot = attribute.slotView
    InlineType(slot) == InlineType.optional &&
    !isPlainBoolean(slot, baseRange(attribute, options).scalaType)
  }

  /** Whether an optional slot of the given Scala type is emitted as a plain `Boolean` defaulting to
    * `false`, instead of an `Option[Boolean]`.
    *
    * The metamodel's `allowed` is kept as an `Option`, because its absence means something else
    * than `false`: `extra_slots` with a `range_expression` and no `allowed` allows extra data, but
    * with `allowed: false` it forbids it.
    */
  private def isPlainBoolean(slot: SlotView, scalaType: String): Boolean =
    scalaType == "Boolean" && slot.uriStr != "https://w3id.org/linkml/allowed"

  /** Build the [[InferredField]]s for a class – one per in-scope slot that has an
    * `equals_expression`.
    *
    * @throws RuntimeException
    *   if an in-scope slot's expression fails to parse, or references an out-of-scope slot
    */
  private def makeInferredFields(classView: ClassView, options: Options): Seq[InferredField] =
    classView.sortedAttributeViews
      .filter(isSingleValuedString)
      .flatMap(attribute => attribute.equalsExpression.mapFast(attribute -> _))
      .map { (attribute, parsed) =>
        val slot = attribute.slotView
        val expression = parsed match {
          case Parsed.Success(value, _) => value
          case f: Parsed.Failure =>
            throw RuntimeException(
              s"Invalid equals_expression on '${classView.name}.${slot.name}': " +
                f.trace().longAggregateMsg,
            )
        }
        InferredField(
          slotName(slot),
          slot.name,
          isOptionField(attribute, options),
          renderExpression(classView, attribute, expression, options),
        )
      }

  /** Render a parsed interpolation expression as a Scala string-concatenation expression.
    *
    * @throws RuntimeException
    *   if the expression references an out-of-scope slot
    */
  private def renderExpression(
      classView: ClassView,
      target: AttributeView,
      expression: StringInterpolationExpression,
      options: Options,
  ): String = {
    import StringInterpolationExpression.{Literal, Substitution}
    if expression.elements.isEmpty then "\"\""
    else
      expression.elements.map {
        case Literal(value) => scalaStringLiteral(value)
        case substitution: Substitution =>
          substitutionProblem(substitution.target).foreach { problem =>
            throw RuntimeException(
              s"The equals_expression on '${classView.name}.${target.slotView.slot.name}' " +
                s"references slot '${substitution.pathString}', which $problem",
            )
          }
          renderPath(substitution.path, options)
      }.mkString(" + ")
  }

  /** Render a resolved slot path as a Scala expression producing the string at its end. Optional
    * links along the way are unwrapped with `inferenceInput`, which fails at runtime if the value
    * is absent. Nested `equals_expression`s are not applied – the value is read as it is.
    */
  private def renderPath(path: Seq[AttributeView], options: Options): String = {
    val access = path.foldLeft(("", "")) { case ((expression, prefix), attribute) =>
      val name = attribute.slotView.slot.name
      val qualified = if prefix.isEmpty then name else s"$prefix.$name"
      val field =
        if expression.isEmpty then scalaCamel(name) else s"$expression.${scalaCamel(name)}"
      val unwrapped =
        if isOptionField(attribute, options) then s"""inferenceInput("$qualified", $field)"""
        else field
      (unwrapped, qualified)
    }._1
    // A string is already what the expression needs. Every other range – numbers, URIs, CURIEs,
    // dates, references, multivalued slots – goes through the `Stringify` typeclass.
    if isSingleValuedString(path.last) then access else s"stringify($access)"
  }

  /** Quote and escape a string so it can be emitted as a Scala string literal. */
  private def scalaStringLiteral(value: String): String = {
    val escaped = value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    s""""$escaped""""
  }

  /** Create a [[ScalaField]] instance, by inferring the [[TypedDefault]] and additional annotations
    * @param attribute
    *   Attribute to construct the [[ScalaField]] instance for
    * @param collectionForm
    *   Collection form of the owner class
    * @param owner
    *   The containing class for the field
    */
  private def makeScalaField(
      attribute: AttributeView,
      collectionForm: CollectionForm,
      owner: ClassView,
      options: Options,
  ): ScalaField = {
    val view = attribute.slotView
    val slot = view.slot
    val name = slotName(view)
    val thisAnnotation = collectionForm match {
      case CollectionForm.SimpleDict(key, value) if slot.name == key => Some("@id")
      case CollectionForm.SimpleDict(key, value) if slot.name == value => Some("@value")
      case CollectionForm.CompactDict(key) if slot.name == key => Some("@id")
      case _ => None
    }
    val aliasAnnotation =
      if view.aliasedName != name then Some(s"@named(${scalaStringLiteral(view.aliasedName)})")
      else None
    val typedDefault = {
      val fromRange = makeTypedDefault(attribute, options)
      if slot.designatesType then designatorDefault(attribute, owner, fromRange) else fromRange
    }
    ScalaField(
      name,
      typedDefault.typeName,
      typedDefault.default,
      Seq() ++ thisAnnotation ++ aliasAnnotation ++ typedDefault.annotations,
      remapMetamodelCombineFunctions(
        view,
        typedDefault.combineFunc,
        baseRange(attribute, options).scalaType,
      ),
      slot.inherited,
      ScalaDoc(slot, view.definingSchema.id, options),
    )
  }
}

object ScalaGenerator {

  /** Whether the generated code has an enum for [[ev]]. An enum without permissible values is a
    * `String` instead.
    */
  def definesEnum(ev: EnumView): Boolean = ev._enum.permissibleValues.nonEmpty

  /** Options for [[ScalaGenerator]].
    *
    * @param package
    *   Scala package to generate the classes in.
    * @param generateEmitPrefixes
    *   Whether to generate a `Prefixes` object holding the model's `emit_prefixes`.
    * @param metadataLanguage
    *   Which language to use for metadata fields (description etc.) in ScalaDocs.
    * @param skipLeafInterfaces
    *   Whether to skip the separate interface for a concrete class that no other class extends
    *   (with `is_a` or `mixins`), and generate it as a single case class named after it instead of
    *   an interface plus a `...Impl` case class. Turn it off if code outside of the generated
    *   sources has to extend the generated classes.
    */
  final case class Options(
      `package`: String = "eu.neverblink.linkml.metamodel",
      generateEmitPrefixes: Boolean = true,
      metadataLanguage: String = "en",
      skipLeafInterfaces: Boolean = true,
  ) extends HasMetadataLanguage

  /** Contains all information necessary for generating a Scala class/trait file analogous to a
    * LinkML [[ClassDefinition]]
    * @param name
    *   Name of the class/trait
    * @param pkg
    *   Scala package to generate the class/trait in
    * @param fields
    *   Fields of the class/trait
    * @param inheritsFrom
    *   List of fields this class/trait should inherit from. Note: single inheritance (is_a /
    *   abstract class) must be first in the list.
    * @param interfaceFields
    *   Fields to include in the interface. Should be a subset of [[fields]] that are
    *   defined/modified directly in this class.
    * @param skipImpl
    *   Whether to skip the case class implementation for instantiable classes.
    * @param caseClassOnly
    *   Whether to generate only a case class named [[name]], with no separate interface. Used for
    *   instantiable classes without children.
    * @param traitInterface
    *   Whether the interface should be a trait. If false, then the interface will be an abstract
    *   class.
    * @param generateSlotCombining
    *   Whether to generate the slot combining methods for this class.
    * @param inferredFields
    *   Fields whose value can be computed from an `equals_expression`. May be empty, in which case
    *   `infer()` is still generated, but does nothing.
    * @param extraSlotsAllowed
    *   Whether the class' `extra_slots` allows data beyond its slots, so that decoding ignores it
    *   instead of failing. Only the class' own `extra_slots` counts, as it is not inherited.
    * @param typeDesignator
    *   The `@typeDesignator` annotation of the interface. None if the class doesn't need one.
    * @param docs
    *   ScalaDoc for generating Scaladoc for this class.
    */
  case class ScalaClassInfo(
      name: String,
      pkg: String,
      fields: Seq[ScalaField],
      inheritsFrom: Seq[String],
      interfaceFields: Set[String],
      skipImpl: Boolean,
      caseClassOnly: Boolean,
      traitInterface: Boolean,
      generateSlotCombining: Boolean,
      inferredFields: Seq[InferredField],
      extraSlotsAllowed: Boolean,
      typeDesignator: Option[String],
      docs: ScalaDoc,
  ) extends Printable:
    /** Builds the Scala representation of a LinkML [[ClassDefinition]]
      * @return
      *   Generated Scala code
      */
    def print: String = {
      val sb = lang.StringBuilder()

      val header =
        s"""package $pkg
           |
           |import eu.neverblink.linkml.runtime.*
           |""".stripMargin
      sb.append(header)
      if caseClassOnly then {
        val classDocs = docs.withParams(fields.map(f => f.name -> f.doc.main))
        val constructorEnd =
          if inheritsFrom.nonEmpty then inheritsFrom.mkString(") extends ", ", ", "") else ")"
        // Without parents there is no `infer()` declaration to override.
        val body = caseClassBody(name, inferDoc, overridesInfer = inheritsFrom.nonEmpty)
        sb.append(indent"""
          |$classDocs
          |${caseClassHeader(name)}
          |    ${fields.map(_.generateCaseClassField).mkString("\n")}
          |$constructorEnd $body
          |""".stripMargin)
        return sb.toString
      }
      if !skipImpl then {
        val caseClassConstructor =
          indent"""
            |/** Base implementation of the [[$name]] LinkML class
            |  * 
            |  * @inheritdoc
            |  */
            |${caseClassHeader(s"${name}Impl")}
            |    ${fields.map(_.generateCaseClassField).mkString("\n")}
            |) extends $name
            |""".stripMargin
        sb.append(
          indent"$caseClassConstructor ${caseClassBody(s"${name}Impl", "", overridesInfer = true)}\n\n",
        )
      }
      val interfaceDef =
        if traitInterface then "trait"
        else "abstract class"
      val inheritanceList = if inheritsFrom.nonEmpty then {
        inheritsFrom.mkString(" extends ", ", ", "")
      } else ""
      val interfaceBody = fields
        .collect { case x if interfaceFields.contains(x.name) => x.generateInterfaceField }
        .mkString("\n")
      // Declared on the interface so callers can infer without knowing the implementation type.
      // The implementation narrows the return type to its own `${name}Impl`.
      val inferDeclaration = indent"""${inferDoc}def infer(): $name
                |""".stripMargin
      // `indent` drops a trailing newline of an interpolated value. So the annotation and the
      // declaration go into one value.
      val declaration = typeDesignator.foldFast(interfaceDef)(_ + "\n" + interfaceDef)
      sb.append(indent"""$docs
                |$declaration $name $inheritanceList {
                |  $interfaceBody
                |
                |  $inferDeclaration
                |}
                |""".stripMargin)
      sb.toString
    }

    /** The opening line of the case class declaration, up to the open parenthesis. */
    private def caseClassHeader(caseClassName: String): String =
      s"${
          if extraSlotsAllowed then "@extraSlotsAllowed\nfinal" else "final"
        } case class $caseClassName("

    /** The body of the case class: slot combining (if enabled) and `infer()`.
      *
      * @param caseClassName
      *   Name of the case class, used as the parameter and return types
      * @param inferDoc
      *   Scaladoc of `infer()`, or empty to inherit it
      * @param overridesInfer
      *   Whether `infer()` overrides a declaration in a parent
      */
    private def caseClassBody(
        caseClassName: String,
        inferDoc: String,
        overridesInfer: Boolean,
    ): String = {
      // TODO LNK-170: consider making infer() a typeclass, so that the generated classes are not
      // bound to inference logic.
      val inferMethod =
        indent"""
          |$inferDoc
          |${if overridesInfer then "override def" else "def"} infer(): $caseClassName =
          |  ${
            if inferredFields.isEmpty then "this"
            else indent"""copy(
                |  ${inferredFields.map(_.generateInferPart).mkString(",\n")}
                |)""".stripMargin
          }
          |""".stripMargin
      val combining =
        if !generateSlotCombining then ""
        else {
          val combineRange =
            "combineRange: (Reference[Element], Reference[Element]) => Reference[Element]"
          indent"""
          |/** Unfolded slot combining procedure `for metaslot in metaslots` from the spec. This variant
          |  * merges ALL SlotDefinition slots.
          |  * @see
          |  *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#algorithm-combine-slots
          |  * @param combineRange
          |  *   Injected range combination function to resolve a circular dependency between metamodel and
          |  *   schema view
          |  */
          |def combineWith(other: $caseClassName, $combineRange): $caseClassName =
          |  copy(
          |    ${fields.map(_.generateCombiningFunctionPart).mkString(",\n")}
          |  )
          |
          |/** Unfolded slot combining procedure `for metaslot in metaslots` from the spec. This variant
          |  * merges INHERITED SlotDefinition slots only.
          |  * @see
          |  *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#algorithm-combine-slots
          |  * @param combineRange
          |  *   Injected range combination function to resolve a circular dependency between metamodel and
          |  *   schema view
          |  */
          |def combineInherited(other: $caseClassName, $combineRange): $caseClassName =
          |  copy(
          |    ${fields
              .collect { case f if f.inherited => f.generateCombiningFunctionPart }
              .mkString(",\n")}
          |  )
          |""".stripMargin
        }
      indent"""
      |{
      |  $combining
      |  $inferMethod
      |}
      |""".stripMargin
    }

  /** Scaladoc of the `infer()` method. */
  private val inferDoc: String =
    """/** Fill in the slots that have an `equals_expression` with their computed values, and
      |  * check that the values already present agree with what their expressions infer.
      |  *
      |  * @throws eu.neverblink.linkml.runtime.InferenceException
      |  *   if a slot's value contradicts the value inferred for it, or if an expression
      |  *   references a slot that has no value
      |  */
      |""".stripMargin

  /** Contains all information necessary for generating a Scala enum file analogous to a LinkML
    * [[EnumDefinition]]
    * @param name
    *   Name of the enum
    * @param pkg
    *   Scala package to generate the enum in
    * @param cases
    *   Cases of the enum
    * @param traitInterface
    *   Whether the enum should be a trait. If false, then the enum will be an abstract class.
    * @param docs
    *   ScalaDoc for generating Scaladoc for this class.
    */
  case class ScalaEnumInfo(
      name: String,
      pkg: String,
      cases: Seq[ScalaEnumCase],
      sealedInterface: Boolean,
      traitInterface: Boolean,
      docs: ScalaDoc,
  ):
    /** Builds the Scala representation of a LinkML [[EnumDefinition]]
      * @return
      *   Generated Scala code
      */
    def generate(): String = {

      val extensibleKind =
        if (sealedInterface) "sealed "
        else ""
      val baseKind =
        if (traitInterface) "trait"
        else "abstract class"

      val kind = extensibleKind + baseKind
      val declaration =
        if (sealedInterface) s"$kind $name derives Stringify"
        else s"$kind $name"

      indent"""package $pkg
         |
         |import eu.neverblink.linkml.runtime.*
         |
         |$docs
         |$declaration
         |
         |object $name {
         |  ${cases.map(_.generateCase).mkString("\n")}
         |}
         |""".stripMargin
    }

  /** Contains elements to include when generating a Scaladoc for a Scala field
    * @param main
    *   Main body of the ScalaDoc
    * @param see
    *   Strings to include in `@see` tags
    * @param notes
    *   Strings to include in `@note` tags
    * @param todos
    *   Strings to include in `@todo` tags
    * @param examples
    *   Strings to include in `@example` tags
    */
  case class ScalaDoc(
      main: String,
      see: Iterable[String],
      notes: Iterable[String],
      todos: Iterable[String],
      examples: Iterable[String],
      params: Seq[(String, String)] = Seq(),
  ) extends Printable:
    /** This ScalaDoc with `@param` tags for the given (name, description) pairs. Pairs with an
      * empty description are left out.
      */
    def withParams(params: Seq[(String, String)]): ScalaDoc =
      copy(params = params.filter(_._2.nonEmpty))

    private def formatTag(sb: lang.StringBuilder, tag: String, content: String): Unit = {
      sb.append(s"  * @$tag\n")
      sb.append(s"  *   $content\n")
    }

    /** Generates code for the Scaladoc */
    def print: String = {
      val sb = lang.StringBuilder()
      val hasAddition =
        params.nonEmpty || see.nonEmpty || notes.nonEmpty || todos.nonEmpty || examples.nonEmpty
      if (main.nonEmpty || hasAddition) {
        sb.append(s"/** $main\n")
        if (hasAddition) {
          sb.append("  *\n")
          params.foreach((name, description) =>
            formatTag(sb, s"param $name", description.replace("\n", "\n  *   ")),
          )
          see.foreach(formatTag(sb, "see", _))
          notes.foreach(formatTag(sb, "note", _))
          todos.foreach(formatTag(sb, "todo", _))
          examples.foreach(formatTag(sb, "example", _))
        }
        sb.append("  */\n")
      }
      sb.toString
    }

  object ScalaDoc {
    def apply(
        metadata: CommonMetadata,
        fromSchema: Uri,
        options: ScalaGenerator.Options,
    ): ScalaDoc = {
      given ScalaGenerator.Options = options
      new ScalaDoc(
        metadata.description.localized
          .mapFast(_.capitalize)
          .getOrElseFast(""),
        metadata.seeAlso.map(_.original) ++
          metadata.aliases.localized
            .reduceOption(_ + ", " + _)
            .mapFast("Aliases: ".concat) ++
          Seq("From schema: ".concat(fromSchema.original)),
        (metadata.notes ++ metadata.comments).localized
          .map(_.capitalize),
        metadata.todos.map(_.capitalize),
        metadata.examples.flatMap(ex =>
          for
            value <- ex.value
            desc <- ex.valueDescription.localized
          yield s"`$value`: $desc",
        ),
      )
    }
  }

  /** Contains all information necessary for generating a Scala field. Can be used to generate a
    * case class, a getter interface or a combining function.
    *
    * @param name
    *   Name of the field, in camelCase.
    * @param typeName
    *   Name of the Scala type.
    * @param default
    *   The default value for the case class field, if applicable.
    * @param annotations
    *   Annotations to add to the case class.
    * @param combineFunc
    *   The runtime function to use for slot combining.
    * @param inherited
    *   If true, include this field in the inherited slot combining.
    * @param doc
    *   [[ScalaDoc]] to provide for the interface field.
    */
  case class ScalaField(
      name: String,
      typeName: String,
      default: Option[String],
      annotations: Seq[String],
      combineFunc: CombineFunction,
      inherited: Boolean,
      doc: ScalaDoc,
  ):
    /** Generate code for a case class field string with annotations and default values */
    def generateCaseClassField: String = {
      val field = default.foldFast(s"$name: $typeName,")(dv => s"$name: $typeName = $dv,")
      s"""${annotations.mkString("\n")}
         |$field
         |""".stripMargin.strip()
    }

    /** Generate code for an interface getter for the field with documentation */
    def generateInterfaceField: String =
      s"${doc.print}def $name: $typeName"

    /** Generate code for a combining function part.
      */
    def generateCombiningFunctionPart: String =
      s"""$name = ${combineFunc.generate(name, "this", "other")}"""

  /** A field whose value can be computed from the slot's `equals_expression`.
    *
    * @param name
    *   Name of the field, in camelCase.
    * @param slotName
    *   Name of the slot as written in the schema, used in error messages at runtime.
    * @param optional
    *   Whether the field is an `Option`, and so can be filled in. Required fields can only be
    *   checked for consistency.
    * @param expression
    *   Scala expression computing the field's value from the other fields of the class.
    */
  case class InferredField(
      name: String,
      slotName: String,
      optional: Boolean,
      expression: String,
  ):
    /** Generate the `copy()` argument that infers or checks this field. */
    def generateInferPart: String =
      if optional then s"""$name = inferOptional("$slotName", $name, $expression)"""
      else s"""$name = inferRequired("$slotName", $name, $expression)"""

  /** Contains all information necessary for generating a Scala enum cases.
    *
    * @param caseName
    *   Name of the enum case.
    * @param objectName
    *   Name of the Scala enum value object.
    * @param enumName
    *   Name of the Scala enum class.
    * @param doc
    *   [[ScalaDoc]] to provide for the enum case.
    */
  case class ScalaEnumCase(
      caseName: String,
      objectName: String,
      enumName: String,
      doc: ScalaDoc,
  ):
    /** Generate code for an enum case */
    def generateCase: String =
      if (caseName == objectName) s"${doc.print}case object $objectName extends $enumName"
      else s"""${doc.print}@named("$caseName") case object $objectName extends $enumName"""

  /** Enum containing information about runtime-provided functions which facilitate the slot
    * combining algorithm.
    *
    * @see
    *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#algorithm-combine-slots
    */
  enum CombineFunction:
    def generate(fieldName: String, param1: String = "v1", param2: String = "v2"): String =
      this match {
        case combineOption(fallback) =>
          s"combineOption($param1.$fieldName, $param2.$fieldName, $fallback)"
        case combineAllOf(expressionType) =>
          val joined = s"combineDistinct($param1.$fieldName, $param2.$fieldName)"
          val withAnyOf = s"combineDistinct($joined, wrapIfNotSubset($param1.anyOf, " +
            s"$param2.anyOf, v => $expressionType(anyOf = v)))"
          s"combineDistinct($withAnyOf, wrapIfDifferent($param1.exactlyOneOf, " +
            s"$param2.exactlyOneOf, v => $expressionType(exactlyOneOf = v)))"
        case _ => s"$this($param1.$fieldName, $param2.$fieldName)"
      }

    case combineOption(fallback: CombineFunction)

    /** Joins the `all_of` lists of two slots. The `any_of` and `exactly_one_of` slots keep only the
      * list of the more specific slot, so the lists of the other slot are also added to `all_of`.
      * As a result, a value must match the `any_of` and `exactly_one_of` lists of both slots.
      *
      * The class with the `allOf` field must also have the `anyOf` and `exactlyOneOf` fields.
      *
      * @param expressionType
      *   The Scala type of the members, which has the `anyOf` and `exactlyOneOf` fields
      */
    case combineAllOf(expressionType: String)
    case combineBoolean, combineSeq, combineNonEmpty, combineDistinct, combineMap, combineFallback,
      combineMax, combineMin, combinePattern, combineRange

  /** Scala type-level information for a field, which has to be inferred together to work correctly.
    *
    * @param typeName
    *   Name of the Scala type to use.
    * @param default
    *   The default value to provide for the field, or None if the field should be required. Must be
    *   compatible with [[typeName]]
    * @param annotations
    *   Annotations implied by the type-level information, e.g. the dict inline style to use, or
    *   whether the default value must be serialized. Empty if none apply.
    * @param combineFunc
    *   The combining function appropriate for the [[typeName]].
    */
  case class TypedDefault(
      typeName: String,
      default: Option[String] = None,
      annotations: Seq[String] = Seq(),
      combineFunc: CombineFunction = CombineFunction.combineFallback,
  )
}

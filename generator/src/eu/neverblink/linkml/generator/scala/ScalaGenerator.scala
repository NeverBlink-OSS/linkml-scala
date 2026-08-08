package eu.neverblink.linkml.generator.scala

import eu.neverblink.linkml.generator.util.*
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview.*
import eu.neverblink.linkml.schemaview.expression.StringInterpolationExpression
import fastparse.Parsed
import java.lang

final class ScalaGenerator(using sv: SchemaView) {
  import ScalaGenerator.*
  import CombineFunction.*

  /** Generate Scala counterparts of all LinkML model elements: Classes, Types and Enums.
    * @param pkg
    *   Scala package to generate the classes in
    * @return
    *   Tuples of form (file name, file content) for all elements in the LinkML model
    */
  def generate(pkg: String, emitEmitPrefixes: Boolean = true): Iterable[(String, String)] =
    generateClasses(pkg) ++ generateEnums(pkg) ++ generateTypeDefinitions(pkg)
      ++ (if emitEmitPrefixes then generateEmitPrefixes(pkg) else None)

  /** Generate Scala counterparts of LinkML classes: case classe implementations for instantiable
    * classes, abstract class interfaces for non-mixin classes, traits for mixins, with LinkML
    * inheritance modeled in abstract classes and traits.
    * @param pkg
    *   Scala package to generate the classes in
    * @return
    *   Tuples of form (file name, file content) for all LinkML classes
    */
  private def generateClasses(pkg: String): Iterable[(String, String)] = {
    for classView <- sv.classes.values yield {
      val cls = classView.cls
      val collectionForm = CollectionForm.of(classView)
      val scalaFields = for attribute <- classView.attributeViews.values.toIndexedSeq yield {
        makeScalaField(attribute, collectionForm)
      }
      val shouldBeTrait = cls.mixin || classView.uriStr == "https://w3id.org/linkml/EnumExpression"
      val isSlotDefinitionClass = classView.uriStr == "https://w3id.org/linkml/SlotDefinition"
      val className = Case.PascalCase(cls.name)
      val interfaceFields =
        (cls.slots.map(_.value) ++ cls.attributes.keys ++ cls.slotUsage.keys).map(slotName).toSet
      val prefixResolver = classView.definingPrefixResolver
      className.concat(".scala") -> (
        if classView.uriStr == "https://w3id.org/linkml/Any" then
          typeDef(
            pkg,
            Case.PascalCase(cls.name),
            "LinkmlAny",
            ScalaDoc(cls, classView.definingSchema.id)(using prefixResolver),
          )
        else
          ScalaClassInfo(
            className,
            pkg,
            scalaFields.sortBy(x => (x.order, x.name)),
            (cls.isA ++ cls.mixins).map(ref => Case.PascalCase(ref.value)).toSeq,
            interfaceFields,
            cls.`abstract` || cls.mixin,
            shouldBeTrait,
            isSlotDefinitionClass,
            makeInferredFields(classView),
            ScalaDoc(classView.materialize, classView.definingSchema.id)(using prefixResolver),
          ).print
      )
    }
  }

  /** Generate Scala counterparts of regular (static) LinkML enums.
    * @param pkg
    *   Scala package to generate the classes in
    * @return
    *   Tuples of form (file name, file content) for all LinkML classes
    */
  private def generateEnums(pkg: String): Iterable[(String, String)] =
    sv.enums.values.flatMap { ev =>
      val en = ev._enum
      if (en.permissibleValues.isEmpty) None
      else {
        val prefixResolver = ev.definingPrefixResolver
        val enumName = Case.PascalCase(en.name)
        val enumCases = en.permissibleValues.values.map(v =>
          ScalaEnumCase(
            caseName = v.text,
            objectName = Case.PascalCase(v.text),
            enumName = enumName,
            doc = ScalaDoc(v, ev.definingSchema.id)(using prefixResolver),
          ),
        ).toSeq
        val enumInfo =
          ScalaEnumInfo(
            enumName,
            pkg,
            enumCases,
            !en.`abstract`,
            en.mixin,
            ScalaDoc(en, ev.definingSchema.id)(using prefixResolver),
          )
            .generate()
        Some(enumName.concat(".scala") -> enumInfo)
      }
    }

  private def generateEmitPrefixes(pkg: String): Option[(String, String)] = {
    if sv.root.emitPrefixes.isEmpty then return None

    val prefixes = sv.root.emitPrefixes.map(prefix =>
      sv.rootPrefixResolver.resolvePrefix(prefix)
        .map(uri => s""""$prefix" -> "$uri",\n""")
        .getOrElse(sys.error(s"Unknown prefix to emit: $prefix")),
    ).mkString
    Some(
      "Prefixes.scala" ->
        indent"""package $pkg
           |
           |// GENERATED FROM LINKML
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

  private def generateTypeDefinitions(pkg: String): Iterable[(String, String)] = {
    sv.types.values.collect {
      case tv if !tv.isPrimitive =>
        val name = Case.PascalCase(tv._type.name)
        val prefixResolver = tv.definingPrefixResolver
        s"$name.scala" -> typeDef(
          pkg,
          name,
          typeToRuntime(tv),
          ScalaDoc(tv._type, tv.definingSchema.id)(using prefixResolver),
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
       |// GENERATED FROM LINKML
       |
       |import eu.neverblink.linkml.runtime.*
       |${doc.print}
       |type $typeName = $typeRange
       |""".stripMargin
  }

  /** Translate a `snake_case` slot name into a Scala `lowerCamelCase` name and quote Scala keywords
    * in backticks.
    * @param snakeCase
    *   name of a slot in `snake_case`
    */
  private def slotName(snakeCase: String): String = {
    val camel = Case.camelCase(snakeCase)
    if (scalaKeywords.contains(camel)) s"`$camel`"
    else camel
  }

  private val scalaKeywords: Set[String] = (
    "abstract case catch class def do else extends final finally for " +
      "forSome if implicit import lazy match new object override package protected return sealed super " +
      "this throw trait try type val var while with yield inline derives end extension using as"
  ).split(' ').toSet

  def typeToRuntime(tv: TypeView): String = tv.runtimeType match {
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
    case UnknownType => tv.inner.base.getOrElse("Unknown")
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
  ): (scalaType: String, baseDefault: Option[String]) = {
    attribute match {
      // Redirect classes with uri == linkml:Any to the runtime class, as by spec it's not a builtin class:
      // From https://linkml.io/linkml/schemas/advanced.html#linkml-any-type
      // "but any class in the schema can take on this roll be being declared as linkml:Any using class_uri"
      case AnyView(slotView, _) =>
        (Case.PascalCase(slotView.derivedRange.resolve.get.name), None)
      case ClassInlineAttributeView(_, _, classView, _) =>
        // Abstract classes and mixins get no `...Impl` case class, so an inlined range pointing at
        // one has to be typed as the interface instead.
        val className = Case.PascalCase(classView.cls.name)
        val hasImpl = !classView.cls.`abstract` && !classView.cls.mixin
        (if hasImpl then s"${className}Impl" else className, None)
      case ClassReferenceAttributeView(_, _, classView, _) =>
        (s"Reference[${Case.PascalCase(classView.cls.name)}]", None)
      case TypeAttributeView(_, _, typeView) =>
        if typeView.isPrimitive then (typeToRuntime(typeView), None)
        else (Case.PascalCase(typeView._type.name), None)
      // True enum support would require working around the dynamic "enums" of LinkML, which I'm sure
      // were a really convenient idea for the biologists, but it adds a lot of complexity for us
      case EnumAttributeView(slotView, _, enumView) =>
        val enumDef = enumView._enum
        if (enumDef.permissibleValues.isEmpty)
          ("String", None) // fallback to String for dynamic enums
        else {
          val enumName = Case.PascalCase(enumDef.name)
          val default: Option[PermissibleValue] = slotView.ifAbsent(enumView)
          (enumName, default.map(p => s"$enumName.${Case.PascalCase(p.text)}"))
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
  private def makeTypedDefault(attribute: AttributeView): TypedDefault = {
    val slot = attribute.slotView
    val (scalaType, defaultValue) = baseRange(attribute)
    // Defaults coming from 'ifabsent' are meaningful and must survive serialization, unlike the
    // implicit "empty" defaults (None / Seq() / Map()) that codecs are free to omit.
    val ifAbsentAnnotation =
      if defaultValue.isDefined then Seq("@serializeDefault") else Seq()
    InlineType(slot) match {
      case InlineType.plain =>
        TypedDefault(scalaType, defaultValue, annotations = ifAbsentAnnotation)
      case InlineType.optional if scalaType == "Boolean" =>
        TypedDefault(
          scalaType,
          default = Some("false"),
          combineFunc = combineBoolean,
        )
      case InlineType.optional =>
        TypedDefault(
          s"Option[$scalaType]",
          default = Some(defaultValue.map(v => s"Some($v)").getOrElse("None")),
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

  /** Remap the [[CombineFunction]] if necessary, handles special metamodel slots.
    *
    * @param slot
    *   Slot to remap the combining function
    * @param rangeCombineFunc
    *   The type-level inferred [[CombineFunction]]
    * @return
    *   The remapped combine functions, or [[rangeCombineFunc]] if no change was necessary
    * @see
    *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#algorithm-combine-slots
    */
  private def remapMetamodelCombineFunctions(
      slot: SlotView,
      rangeCombineFunc: CombineFunction,
  ): CombineFunction =
    slot.uriStr match {
      case "https://w3id.org/linkml/maximum_value" => combineOption(combineMax)
      case "https://w3id.org/linkml/minimum_value" => combineOption(combineMin)
      case "https://w3id.org/linkml/pattern" => combineOption(combinePattern)
      case "https://w3id.org/linkml/range" => combineOption(combineRange)
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
  private def isOptionField(attribute: AttributeView): Boolean = {
    val isOptional = InlineType(attribute.slotView) == InlineType.optional
    isOptional && baseRange(attribute).scalaType != "Boolean"
  }

  /** Build the [[InferredField]]s for a class – one per in-scope slot that has an
    * `equals_expression`.
    *
    * @throws RuntimeException
    *   if an in-scope slot's expression fails to parse, or references an out-of-scope slot
    */
  private def makeInferredFields(classView: ClassView): Seq[InferredField] =
    classView.attributeViews.values.toSeq
      .filter(isSingleValuedString)
      .flatMap(attribute => attribute.equalsExpression.map(attribute -> _))
      .map { (attribute, parsed) =>
        val slot = attribute.slotView.slot
        val expression = parsed match {
          case Parsed.Success(value, _) => value
          case f: Parsed.Failure =>
            throw RuntimeException(
              s"Invalid equals_expression on '${classView.name}.${slot.name}': " +
                f.trace().longAggregateMsg,
            )
        }
        InferredField(
          slotName(slot.name),
          slot.name,
          isOptionField(attribute),
          renderExpression(classView, attribute, expression),
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
          renderPath(substitution.path)
      }.mkString(" + ")
  }

  /** Render a resolved slot path as a Scala expression producing the string at its end. Optional
    * links along the way are unwrapped with `inferenceInput`, which fails at runtime if the value
    * is absent. Nested `equals_expression`s are not applied – the value is read as it is.
    */
  private def renderPath(path: Seq[AttributeView]): String = {
    val access = path.foldLeft(("", "")) { case ((expression, prefix), attribute) =>
      val name = attribute.slotView.slot.name
      val qualified = if prefix.isEmpty then name else s"$prefix.$name"
      val field =
        if expression.isEmpty then slotName(name) else s"$expression.${slotName(name)}"
      val unwrapped =
        if isOptionField(attribute) then s"""inferenceInput("$qualified", $field)"""
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
    */
  private def makeScalaField(
      attribute: AttributeView,
      collectionForm: CollectionForm,
  ): ScalaField = {
    val v = attribute.slotView
    val slot = v.slot
    val name = slotName(slot.name)
    // Move id / value to the front, regardless of rank
    val (thisAnnotation, order) = collectionForm match {
      case CollectionForm.SimpleDict(key, value) if slot.name == key => (Some("@id"), -2)
      case CollectionForm.SimpleDict(key, value) if slot.name == value => (Some("@value"), -1)
      case CollectionForm.CompactDict(key) if slot.name == key => (Some("@id"), -2)
      case _ => (None, slot.rank.getOrElse(10_000))
    }
    val aliasAnnotation =
      slot.alias
        .orElse(if slot.name != name then Some(slot.name) else None)
        .map(s => s"@named(\"${Case.deSpaceCase(s)}\")")
    val typedDefault = makeTypedDefault(attribute)
    ScalaField(
      name,
      typedDefault.typeName,
      typedDefault.default,
      Seq() ++ thisAnnotation ++ aliasAnnotation ++ typedDefault.annotations,
      remapMetamodelCombineFunctions(v, typedDefault.combineFunc),
      order,
      // TODO LNK-124: remove when resolved in linkml-model
      // Patch to allow for the slot rank to be inherited.
      slot.inherited || slot.name == "rank",
      ScalaDoc(slot, v.definingSchema.id)(using v.definingPrefixResolver),
    )
  }
}

object ScalaGenerator {

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
    * @param traitInterface
    *   Whether the interface should be a trait. If false, then the interface will be an abstract
    *   class.
    * @param generateSlotCombining
    *   Whether to generate the slot combining methods for this class.
    * @param inferredFields
    *   Fields whose value can be computed from an `equals_expression`. May be empty, in which case
    *   `infer()` is still generated, but does nothing.
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
      traitInterface: Boolean,
      generateSlotCombining: Boolean,
      inferredFields: Seq[InferredField],
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
           |// GENERATED FROM LINKML
           |
           |import eu.neverblink.linkml.runtime.*
           |""".stripMargin
      sb.append(header)
      if !skipImpl then {
        val caseClassConstructor =
          indent"""
            |/** Base implementation of the [[$name]] LinkML class
            |  * 
            |  * @inheritdoc
            |  */
            |final case class ${name}Impl(
            |    ${fields.map(_.generateCaseClassField).mkString("\n")}
            |) extends $name
            |""".stripMargin
        // TODO LNK-170: consider making infer() a typeclass, so that the generated classes are not
        // bound to inference logic.
        val inferMethod =
          indent"""
            |override def infer(): ${name}Impl =
            |  ${
              if inferredFields.isEmpty then "this"
              else indent"""copy(
                  |  ${inferredFields.map(_.generateInferPart).mkString(",\n")}
                  |)""".stripMargin
            }
            |""".stripMargin
        val caseClassBody = {
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
              |def combineWith(other: ${name}Impl, $combineRange): ${name}Impl =
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
              |def combineInherited(other: ${name}Impl, $combineRange): ${name}Impl =
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

        sb.append(indent"$caseClassConstructor $caseClassBody\n\n")
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
      val inferDeclaration =
        indent"""/** Fill in the slots that have an `equals_expression` with their computed values, and
                |  * check that the values already present agree with what their expressions infer.
                |  *
                |  * @throws eu.neverblink.linkml.runtime.InferenceException
                |  *   if a slot's value contradicts the value inferred for it, or if an expression
                |  *   references a slot that has no value
                |  */
                |def infer(): $name
                |""".stripMargin
      sb.append(indent"""$docs
                |$interfaceDef $name $inheritanceList {
                |  $interfaceBody
                |
                |  $inferDeclaration
                |}
                |""".stripMargin)
      sb.toString
    }

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
         |// GENERATED FROM LINKML
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
  ) extends Printable:
    private def formatTag(sb: lang.StringBuilder, tag: String, content: String): Unit = {
      sb.append(s"  * @$tag\n")
      sb.append(s"  *   $content\n")
    }

    /** Generates code for the Scaladoc */
    def print: String = {
      val sb = lang.StringBuilder()
      val hasAddition = see.nonEmpty || notes.nonEmpty || todos.nonEmpty || examples.nonEmpty
      if (main.nonEmpty || hasAddition) {
        sb.append(s"/** $main\n")
        if (hasAddition) {
          sb.append("  *\n")
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
    def apply(metadata: CommonMetadata, fromSchema: Uri)(using
        pr: PrefixResolver,
    ): ScalaDoc = {
      new ScalaDoc(
        metadata.description.map(_.capitalize).getOrElse(""),
        metadata.seeAlso.map(_.uri) ++
          metadata.aliases.reduceOption(_ + ", " + _).map("Aliases: " + _) ++
          Seq("From schema: " + fromSchema.uri),
        metadata.notes.map(_.capitalize) ++
          metadata.comments.map(_.capitalize),
        metadata.todos.map(_.capitalize),
        metadata.examples.flatMap(ex =>
          for
            value <- ex.value
            desc <- ex.valueDescription
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
    * @param order
    *   The order of the field, smaller values appear first.
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
      order: Int,
      inherited: Boolean,
      doc: ScalaDoc,
  ):
    /** Generate code for a case class field string with annotations and default values */
    def generateCaseClassField: String = {
      val field = default match {
        case Some(defaultValue) => s"$name: $typeName = $defaultValue,"
        case _ => s"$name: $typeName,"
      }
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
        case _ => s"$this($param1.$fieldName, $param2.$fieldName)"
      }

    case combineOption(fallback: CombineFunction)
    case combineBoolean, combineSeq, combineMap, combineFallback, combineMax, combineMin,
      combinePattern, combineRange

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

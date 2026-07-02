package eu.neverblink.linkml.generator.scala

import eu.neverblink.linkml.generator.util.*
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview.*
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
    generateClasses(pkg) ++ generateEnums(pkg) // TODO: add types
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
      val slotMap = classView.derivedAttributes
      val inlineStyle = CollectionForm.of(slotMap)
      val scalaFields = for slot <- slotMap.values.toIndexedSeq yield {
        makeScalaField(slot, inlineStyle)
      }
      val shouldBeTrait = cls.mixin || classView.uriStr == "https://w3id.org/linkml/EnumExpression"
      val isSlotDefinitionClass = classView.uriStr == "https://w3id.org/linkml/SlotDefinition"
      val className = Case.PascalCase(cls.name)
      val interfaceFields =
        (cls.slots.map(_.value) ++ cls.attributes.keys ++ cls.slotUsage.keys).map(slotName).toSet
      className + ".scala" -> (
        if classView.uriStr == "https://w3id.org/linkml/Any" then
          anyStub(pkg, Case.PascalCase(cls.name))
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
            ScalaDoc(cls),
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
        val enumName = Case.PascalCase(en.name)
        val enumCases = en.permissibleValues.values.map(v =>
          ScalaEnumCase(
            caseName = v.text,
            objectName = Case.PascalCase(v.text),
            enumName = enumName,
            doc = ScalaDoc(v),
          ),
        ).toSeq
        val enumInfo =
          ScalaEnumInfo(enumName, pkg, enumCases, !en.`abstract`, en.mixin, ScalaDoc(en))
            .generate()
        Some(enumName + ".scala" -> enumInfo)
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

  /** Generate a stub file for classes with the linkml:Any class URI, aliasing them to the runtime
    * [[Anything]] class.
    *
    * @param pkg
    *   Package to generate the stub in
    * @param anyName
    *   Name of the class to generate an Anything stub for
    * @return
    *   The code for the linkml:Any stub
    */
  private def anyStub(pkg: String, anyName: String): String = {
    s"""package $pkg
       |
       |// GENERATED FROM LINKML
       |
       |import eu.neverblink.linkml.runtime.LinkMlAny
       |
       |type $anyName = LinkMlAny
       |""".stripMargin
  }

  /** Translate a `snake_case` slot name into a Scala `lowerCamelCase` name and quote Scala keywords
    * in backticks.
    * @param snakeCase
    *   name of a slot in `snake_case`
    */
  private def slotName(snakeCase: String): String = {
    val camel = Case.camelCase(snakeCase)
    if scalaKeywords.contains(camel)
    then "`" + camel + "`"
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
    case AnyType => "LinkMlAny"

    case DateOrDateTimeType => "ZonedDateTime"
    case DateType => "ZonedDateTime"
    case DateTimeType => "ZonedDateTime"
    case TimeType => "ZonedDateTime"

    case UriOrCurieType => "UriOrCurie"
    case UriType => "Uri"
    case CurieType => "Curie"
    case NcNameType => "NcName"
    case UnknownType => "Unknown"
  }

  /** Translates a LinkML range value to the appropriate Scala type.
    *
    * @param range
    *   Value of the LinkML range
    * @param isInlined
    *   Whether the slot is declared as inlined. Note: The slot may be implicitly inlined when the
    *   range class does not have an identifier.
    * @return
    *   Scala type which best represents the range
    */
  private def baseRange(range: Reference[ElementView[?]], isInlined: Boolean): String = {
    range.resolve.get match {
      case classView: ClassView =>
        // Redirect classes with uri == linkml:Any to the runtime class, as by spec it's not a builtin class:
        // From https://linkml.io/linkml/schemas/advanced.html#linkml-any-type
        // "but any class in the schema can take on this roll be being declared as linkml:Any using class_uri"
        val className = Case.PascalCase(classView.cls.name)
        if (classView.uriStr == "https://w3id.org/linkml/Any") className
        else if (isInlined) s"${className}Impl"
        else s"Reference[$className]"
      case typeView: TypeView =>
        typeToRuntime(typeView)
      // True enum support would require working around the dynamic "enums" of LinkML, which I'm sure
      // were a really convenient idea for the biologists, but it adds a lot of complexity for us
      case enumView: EnumView =>
        val enumDef = enumView._enum
        if (enumDef.permissibleValues.isEmpty) "String" // fallback to String for dynamic enums
        else {
          val enumName = Case.PascalCase(enumDef.name)
          if (isInlined) enumName
          else s"Reference[$enumName]"
        }
      case _ => throw RuntimeException(s"Couldn't map range $range")
    }
  }

  /** Create a [[TypedDefault]] containing all the type-level information for a slot's range / Scala
    * field type. Handles implicit / explicit inlines, inline styles, combining functions and
    * default values (sans `ifabasent`).
    * @param slot
    *   Slot to derive the type-level information for
    * @return
    *   The inferred [[TypedDefault]]
    */
  private def makeTypedDefault(slot: SlotView): TypedDefault = {
    val range = slot.derivedRangeView
    val inlined = slot.derivedInlined
    val base = baseRange(range, inlined)
    InlineType(slot) match {
      case InlineType.plain => TypedDefault(base)
      case InlineType.optional if base == "Boolean" =>
        TypedDefault(
          base,
          default = Some("false"),
          combineFunc = combineBoolean,
        )
      case InlineType.optional =>
        TypedDefault(
          s"Option[$base]",
          default = Some("None"),
          combineFunc = combineOption(combineFallback),
        )
      case InlineType.list =>
        TypedDefault(
          s"Seq[$base]",
          default = if slot.slot.required then None else Some("Seq()"),
          combineFunc = combineSeq,
        )
      case InlineType.dict(style) =>
        val annotation = style match {
          case _: CollectionForm.SimpleDict => "@simpleDict"
          case _: CollectionForm.CompactDict => "@compactDict"
        }
        TypedDefault(
          s"Map[String, $base]",
          default = if slot.slot.required then None else Some("Map()"),
          annotation = Some(annotation),
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

  /** Create a [[ScalaField]] instance, by inferring the [[TypedDefault]] and additional annotations
    * @param v
    *   Slot to construct the [[ScalaField]] instance for
    * @param inlineStyle
    *   Inline style of the owner class
    */
  private def makeScalaField(v: SlotView, inlineStyle: CollectionForm): ScalaField = {
    val slot = v.slot
    val name = slotName(slot.name)
    // Move id / value to the front, regardless of rank
    val (thisAnnotation, order) = inlineStyle match {
      case CollectionForm.SimpleDict(key, value) if slot.name == key => (Some("@id"), -2)
      case CollectionForm.SimpleDict(key, value) if slot.name == value => (Some("@value"), -1)
      case CollectionForm.CompactDict(key) if slot.name == key => (Some("@id"), -2)
      case _ => (None, slot.rank.getOrElse(10_000))
    }
    val aliasAnnotation =
      slot.alias
        .orElse(if slot.name != name then Some(slot.name) else None)
        .map(s => s"@named(\"${Case.deSpaceCase(s)}\")")
    val typedDefault = makeTypedDefault(v)
    ScalaField(
      name,
      typedDefault.typeName,
      typedDefault.default,
      Seq() ++ thisAnnotation ++ aliasAnnotation ++ typedDefault.annotation,
      remapMetamodelCombineFunctions(v, typedDefault.combineFunc),
      order,
      slot.inherited,
      ScalaDoc(slot),
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
            |case class ${name}Impl(
            |    ${fields.map(_.generateCaseClassField).mkString("\n")}
            |) extends $name
            |""".stripMargin
        val caseClassBody =
          if !generateSlotCombining then ""
          else {
            val combineRange =
              "combineRange: (Reference[Element], Reference[Element]) => Reference[Element]"
            val combiningFunctions =
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
                  .filter(_.inherited)
                  .map(_.generateCombiningFunctionPart)
                  .mkString(",\n")}
              |  )
              |""".stripMargin
            indent"""
            |{
            |  $combiningFunctions
            |}
            |""".stripMargin
          }

        sb.append(indent"$caseClassConstructor $caseClassBody\n\n")
      }
      val interfaceDef =
        if traitInterface then "trait"
        else "abstract class"
      val inheritanceList = if inheritsFrom.nonEmpty then {
        " extends " + inheritsFrom.mkString(", ")
      } else ""
      val interfaceBody = fields
        .filter(x => interfaceFields.contains(x.name))
        .map(_.generateInterfaceField).mkString("\n")
      sb.append(indent"""$docs
                |$interfaceDef $name $inheritanceList {
                |  $interfaceBody
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

      indent"""package $pkg
         |
         |// GENERATED FROM LINKML
         |
         |import eu.neverblink.linkml.runtime.*
         |
         |$docs
         |$kind $name
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
    def apply(metadata: CommonMetadata)(using sv: SchemaView): ScalaDoc = {
      // TODO LNK-115: We should use `metadata`'s prefixes here, not root's
      given PrefixResolver = sv.rootPrefixResolver
      new ScalaDoc(
        metadata.description.map(_.capitalize).getOrElse(""),
        metadata.seeAlso.map(_.uri) ++
          metadata.aliases.reduceOption(_ + ", " + _).map("Aliases: " + _) ++
          metadata.fromSchema.map("From schema: " + _.uri),
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
         |""".stripMargin
    }

    /** Generate code for an interface getter for the field with documentation */
    def generateInterfaceField: String =
      s"${doc.print}def $name: $typeName\n\n"

    /** Generate code for a combining function part.
      */
    def generateCombiningFunctionPart: String =
      s"""$name = ${combineFunc.generate(name, "this", "other")}"""

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
    * @param annotation
    *   The range inlining annotation specifying which dict inline style should be used. None if not
    *   applicable.
    * @param combineFunc
    *   The combining function appropriate for the [[typeName]].
    */
  case class TypedDefault(
      typeName: String,
      default: Option[String] = None,
      annotation: Option[String] = None,
      combineFunc: CombineFunction = CombineFunction.combineFallback,
  )
}

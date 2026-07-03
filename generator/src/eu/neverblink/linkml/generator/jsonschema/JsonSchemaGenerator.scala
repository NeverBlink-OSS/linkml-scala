package eu.neverblink.linkml.generator.jsonschema

import eu.neverblink.linkml.metamodel.Anything
import eu.neverblink.linkml.schemaview.*
import sttp.apispec.{AnySchema, Pattern, Schema, SchemaFormat, SchemaType}
import sttp.apispec.circe.encoderSchema

import java.lang
import scala.collection.immutable
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class JsonSchemaGenerator(using sv: SchemaView) {
  import JsonSchemaGenerator.*

  /** Translate a class name into a JSON Schema form, respecting aliases and LinkML casing rules
    */
  protected def className(cls: ClassView): MappedClassName = {
    cls.cls.alias.getOrElse(Case.PascalCase(cls.cls.name))
  }

  /** Translate a slot name into a JSON Schema form, respecting aliases and LinkML casing rules
    */
  protected def slotName(slot: SlotView): MappedSlotName = {
    slot.slot.alias.getOrElse(Case.deSpaceCase(slot.slot.name))
  }

  /** Generate a Schema for a specific slot, which maps to a JSON Schema property.
    * @param slot
    *   The slot to define a JSON Schema property for
    * @param needKeyless
    *   Mutable set this method will add to if it requires a keyless class to be defined in `$defs`
    *   for CompactDict form inlining. The slot will be the key to omit from required fields.
    * @param needValue
    *   Mutable set this method will add to if it requires a value def to be defined in `$defs` for
    *   SimpleDict form inlining. The slot will be the value to omit from required fields.
    * @return
    *   A tuple of the mapped slot name and the JSON [[Schema]] for the property value
    */
  private def generateSlotSchema(
      slot: SlotView,
      needKeyless: mutable.Set[(MappedClassName, MappedSlotName)],
      needValue: mutable.Set[(MappedClassName, MappedSlotName)],
  ): (MappedSlotName, Schema) = {
    val range = slot.derivedRangeView.resolve.get
    lazy val referenceSchema = Schema(SchemaType.String)
      .copy($comment = Some(s"Reference to an instance of the '${range.inner.name}' LinkML class"))
    val slotSchema = range match {
      case classView: ClassView =>
        if classView.uriStr == "https://w3id.org/linkml/Any" then Schema.Empty
        else if !slot.derivedInlined && slot.slot.multivalued then referenceSchema.arrayOf
        else if !slot.derivedInlined then referenceSchema
        else {
          val mappedClassName = className(classView)
          lazy val slotMap = classView.derivedAttributes
          InlineType(slot) match {
            case InlineType.plain =>
              Schema.referenceTo("#/$defs/", mappedClassName)
            case InlineType.optional =>
              Schema.referenceTo("#/$defs/", mappedClassName) // TODO LNK-34: or null
            case InlineType.list =>
              Schema.referenceTo("#/$defs/", mappedClassName).arrayOf // TODO LNK-34: or null?
            case InlineType.dict(CollectionForm.CompactDict(key)) =>
              needKeyless.add(mappedClassName -> slotName(slotMap(key)))
              Schema.referenceTo(
                "#/$defs/",
                mappedClassName + "__identifier_optional",
              ).dictOf // TODO LNK-34: or null?
            case InlineType.dict(CollectionForm.SimpleDict(key, value)) =>
              needValue.add(mappedClassName -> slotName(slotMap(value)))
              Schema.referenceTo(
                "#/$defs/",
                mappedClassName + "__simple_dict_value",
              ).dictOf // TODO LNK-34: or null
          }
        }
      case typeView: TypeView =>
        if slot.slot.multivalued then typeToRuntime(typeView).arrayOf
        else typeToRuntime(typeView)
      // TODO LNK-32: True enums
      case _: EnumView => Schema(SchemaType.String)
      case _ => throw RuntimeException(s"Couldn't map range '${range.inner.name}'")
    }
    slotName(slot) -> slotSchema.copy(
      title = slot.slot.title,
      description = slot.slot.description,
      minimum = toBigDecimalOpt(slot.slot.minimumValue),
      maximum = toBigDecimalOpt(slot.slot.maximumValue),
      pattern = slot.slot.pattern.map(Pattern(_)),
    )
  }

  private def toBigDecimalOpt(x: Option[Anything]): Option[BigDecimal] = x match {
    case Some(v) =>
      try new Some(BigDecimal(v.value.trim))
      catch {
        case ex if NonFatal(ex) => None
      }
    case _ => None
  }

  /** Generate the JSON Schema, but keep it in the [[Schema]] model
    *
    * @param open
    *   Whether the generated JSON Schema should allow `additionalProperties` for classes
    * @param treeRootOverride
    *   If defined, override the schema `tree_root` class with the one provided
    * @param treeRootInlineTypeOverride
    *   If defined, override the `tree_root_as` extension of the tree root class with the one
    *   provided.
    * @return
    *   JSON Schema in the [[Schema]] model
    */
  final def generate(
      open: Boolean = false,
      treeRootOverride: Option[String] = None,
      treeRootInlineTypeOverride: Option[String] = None,
  ): Schema = {
    val maybeTreeRoot = sv.treeRootWithOverride(treeRootOverride) match {
      case Success(value) => value
      case Failure(exception) => throw exception
    }
    val needKeyless = mutable.Set.empty[(MappedClassName, MappedSlotName)]
    val needValue = mutable.Set.empty[(MappedClassName, MappedSlotName)]
    // If a tree root is defined, only include classes reachable from the tree root (pruning).
    // Otherwise, include all classes in the schema view.
    val classes = maybeTreeRoot
      .map(root => sv.classesReachableFrom(root, includeAncestors = false, inlinedOnly = true))
      .getOrElse(sv.classes)
    val defs = for cls <- classes.values yield {
      val slots = cls.derivedAttributes
      val properties = for slot <- slots.values yield {
        generateSlotSchema(slot, needKeyless, needValue)
      }
      val requiredSlots = slots.values.filter(_.slot.required).map(_.slot.name).toList
      className(cls) -> Schema(SchemaType.Object).copy(
        required = requiredSlots,
        properties = properties.to(immutable.ListMap),
        additionalProperties = if open then Some(AnySchema.Anything) else Some(AnySchema.Nothing),
        title = cls.cls.title,
        description = cls.cls.description,
      )
    }
    val defMap = defs.toMap
    val defsKeyless = for (className, idField) <- needKeyless yield {
      val classSchema = defMap(className)
      className + "__identifier_optional" -> classSchema.copy(required =
        classSchema.required.filter(_ != idField),
      )
    }
    val defsValues = for (className, valueField) <- needValue yield {
      val simpleDict = defMap(className)
      className + "__simple_dict_value" -> simpleDict.properties(valueField).asInstanceOf[Schema]
    }
    val baseSchema = maybeTreeRoot match {
      case Some(treeRoot) =>
        // TODO LNK-97: cover this with tests
        val classSchema = Schema.referenceTo("#/$defs/", className(treeRoot))
        val inlineType = treeRoot.treeRootInlineType(treeRootInlineTypeOverride)
        inlineType match {
          case InlineType.plain => classSchema // object (mandatory)
          case InlineType.optional =>
            Schema.oneOf(List(classSchema, Schema.Null), discriminator = None) // object or null
          case InlineType.list =>
            Schema(SchemaType.Array).copy(items = Some(classSchema)) // array of objects
          case _ =>
            throw NotImplementedError(
              s"Tree root inline type '$inlineType' is not implemented for JSON Schema.",
            )
        }
      case None => Schema.Empty
    }
    baseSchema.copy(
      $schema = Some("https://json-schema.org/draft/2020-12/schema"),
      $id = Some(sv.root.id.uri(using sv.rootPrefixResolver)),
      title = Some(sv.root.title.getOrElse(sv.root.name)),
      description = sv.root.description,
      $defs = Some((defs ++ defsKeyless ++ defsValues).to(immutable.ListMap)),
    )
  }

  /** Generate the JSON Schema and serialize it
    *
    * @param open
    *   Whether the generated JSON Schema should allow `additionalProperties` for classes
    * @param treeRootOverride
    *   If defined, override the schema `tree_root` class with the one provided
    * @return
    *   Serialized JSON Schema
    */
  final def serialize(open: Boolean = false, treeRootOverride: Option[String] = None): String =
    encoderSchema(generate(open, treeRootOverride)).spaces2
}

object JsonSchemaGenerator {

  /** Translate the [[RuntimeType]] of the provided type view into the appropriate JSON Schema.
    * Provides formats for date-times and URI/CURIE.
    */
  def typeToRuntime(tv: TypeView): Schema = tv.runtimeType match {
    case StringType => Schema(SchemaType.String)
    case IntegerType => Schema(SchemaType.Integer)
    case FloatType => Schema(SchemaType.Number)
    case DoubleType => Schema(SchemaType.Number)
    case BooleanType => Schema(SchemaType.Boolean)
    case DecimalType => Schema(SchemaType.Number)
    case AnyType => Schema.Empty
    case DateType => Schema(SchemaType.String).copy(format = Some(SchemaFormat.Date))
    case DateTimeType => Schema(SchemaType.String).copy(format = Some(SchemaFormat.DateTime))
    case TimeType => Schema(SchemaType.String).copy(format = Some("time"))
    case UriOrCurieType =>
      Schema.Empty.copy(anyOf =
        List(
          Schema(SchemaType.String).copy(format = Some("uri")),
          Schema(SchemaType.String).copy(format = Some("curie")),
        ),
      )
    case UriType => Schema(SchemaType.String).copy(format = Some("uri"))
    case CurieType => Schema(SchemaType.String).copy(format = Some("curie"))
    case NcNameType => Schema(SchemaType.String).copy(format = Some("ncname"))
    case UnknownType => Schema.Empty
  }

  type MappedClassName = String
  type MappedSlotName = String

  extension (schema: Schema)
    def arrayOf: Schema = Schema(SchemaType.Array).copy(items = Some(schema))
    def dictOf: Schema = Schema(SchemaType.Object).copy(additionalProperties = Some(schema))
}

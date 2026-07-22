package eu.neverblink.linkml.generator.jsonschema

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.metamodel.Anything
import eu.neverblink.linkml.schemaview.*
import sttp.apispec.{
  AnySchema,
  ExampleMultipleValue,
  ExampleSingleValue,
  ExampleValue,
  Pattern,
  Schema,
  SchemaFormat,
  SchemaLike,
  SchemaType,
}

import java.lang
import scala.collection.immutable
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class JsonSchemaGenerator(using sv: SchemaView) {
  import JsonSchemaGenerator.*

  /** Translate a class name into a JSON Schema form, respecting aliases and LinkML casing rules
    */
  protected def className(cls: ClassView): MappedClassName =
    cls.cls.alias.getOrElse(Case.PascalCase(cls.cls.name))

  /** Translate a slot name into a JSON Schema form, respecting aliases and LinkML casing rules
    */
  protected def slotName(slot: SlotView): MappedSlotName =
    slot.slot.alias.getOrElse(Case.deSpaceCase(slot.slot.name))

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
    val slotSchema = range match {
      case classView: ClassView =>
        if (classView.uriStr == "https://w3id.org/linkml/Any") Schema.Empty
        else if (slot.derivedInlined) {
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
        } else {
          val referenceSchema = stringSchema
            .copy($comment =
              Some(s"Reference to an instance of the '${range.inner.name}' LinkML class"),
            )
          if (slot.slot.multivalued) referenceSchema.arrayOf
          else referenceSchema
        }
      case typeView: TypeView =>
        if slot.slot.multivalued then typeToRuntime(typeView).arrayOf
        else typeToRuntime(typeView)
      case enumView: EnumView =>
        val referenceSchema = Schema.referenceTo("#/$defs/", enumView._enum.name)
        if (slot.slot.multivalued) referenceSchema.arrayOf
        else referenceSchema
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
    val query = maybeTreeRoot match {
      case Some(root) => sv.derivedReachabilityQuery(Seq(root), true)
      case None => IncludeAllReachabilityQuery()
    }
    val defsClasses = for cls <- sv.classes.values if query.reachable(cls) yield {
      val slots = cls.derivedAttributes
      val properties = for slot <- slots.values yield {
        generateSlotSchema(slot, needKeyless, needValue)
      }
      val requiredSlots = slots.values.collect {
        case s if s.slot.required => s.slot.name
      }
      className(cls) -> objectSchema.copy(
        required = requiredSlots.toList,
        properties = properties.to(immutable.ListMap),
        additionalProperties = Some(if (open) AnySchema.Anything else AnySchema.Nothing),
        title = cls.cls.title,
        description = cls.cls.description,
      )
    }
    val defsEnums = for ev <- sv.enums.values yield {
      val enum_ = ev._enum
      enum_.name -> objectSchema.copy(
        `type` = Some(List(SchemaType.String)),
        `enum` = Some(enum_.permissibleValues.keys.map(ExampleSingleValue(_)).toList),
        title = enum_.title,
        description = enum_.description,
      )
    }
    val defMap = defsClasses.toMap
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
            arraySchema.copy(items = Some(classSchema)) // array of objects
          case _ =>
            throw NotImplementedError(
              s"Tree root inline type '$inlineType' is not implemented for JSON Schema.",
            )
        }
      case _ => Schema.Empty
    }
    baseSchema.copy(
      $schema = Some("https://json-schema.org/draft/2020-12/schema"),
      $id = Some(sv.root.id.uri(using sv.rootPrefixResolver)),
      title = Some(sv.root.title.getOrElse(sv.root.name)),
      description = sv.root.description,
      $defs = Some((defsClasses ++ defsEnums ++ defsKeyless ++ defsValues).to(immutable.ListMap)),
    )
  }

  /** Generate the JSON Schema and serialize it
    *
    * @param open
    *   Whether the generated JSON Schema should allow `additionalProperties` for classes
    * @param treeRootOverride
    *   If defined, override the schema `tree_root` class with the one provided
    * @param indentationStep
    *   number of spaces in pretty print indentation of JSON Schema
    * @return
    *   Serialized JSON Schema
    */
  final def serialize(
      open: Boolean = false,
      treeRootOverride: Option[String] = None,
      indentationStep: Int = 2,
  ): String =
    writeToString(generate(open, treeRootOverride), WriterConfig.withIndentionStep(indentationStep))
}

object JsonSchemaGenerator {

  /** Translate the [[RuntimeType]] of the provided type view into the appropriate JSON Schema.
    * Provides formats for date-times and URI/CURIE.
    */
  def typeToRuntime(tv: TypeView): Schema = tv.runtimeType match {
    case StringType => stringSchema
    case IntegerType => integerSchema
    case FloatType => numberSchema
    case DoubleType => numberSchema
    case BooleanType => booleanSchema
    case DecimalType => numberSchema
    case AnyType => Schema.Empty
    case DateType => stringSchema.copy(format = Some(SchemaFormat.Date))
    case DateTimeType => stringSchema.copy(format = Some(SchemaFormat.DateTime))
    case TimeType => stringSchema.copy(format = Some("time"))
    case UriOrCurieType =>
      Schema.Empty.copy(anyOf =
        List(
          stringSchema.copy(format = Some("uri")),
          stringSchema.copy(format = Some("curie")),
        ),
      )
    case UriType => stringSchema.copy(format = Some("uri"))
    case CurieType => stringSchema.copy(format = Some("curie"))
    case NcNameType => stringSchema.copy(format = Some("ncname"))
    case UnknownType => Schema.Empty
  }

  type MappedClassName = String
  type MappedSlotName = String

  extension (schema: Schema)
    def arrayOf: Schema = arraySchema.copy(items = Some(schema))
    def dictOf: Schema = objectSchema.copy(additionalProperties = Some(schema))

  private implicit lazy val codec: JsonValueCodec[Schema] = {
    implicit val schemaLikeCodec: JsonValueCodec[SchemaLike] = new JsonValueCodec {
      override def decodeValue(in: JsonReader, default: SchemaLike): SchemaLike = ???

      override def encodeValue(x: SchemaLike, out: JsonWriter): Unit = x match {
        case s: Schema => codec.encodeValue(s, out)
        case AnySchema.Anything => out.writeVal(true)
        case AnySchema.Nothing => out.writeVal(false)
      }

      override def nullValue: SchemaLike = ???
    }
    implicit val listOfSchemaTypeCodec: JsonValueCodec[List[SchemaType]] = new JsonValueCodec {
      override def decodeValue(in: JsonReader, default: List[SchemaType]): List[SchemaType] = ???

      override def encodeValue(xs: List[SchemaType], out: JsonWriter): Unit =
        if (xs.size == 1) out.writeNonEscapedAsciiVal(xs.head.value)
        else {
          out.writeArrayStart()
          xs.foreach(x => out.writeNonEscapedAsciiVal(x.value))
          out.writeArrayEnd()
        }

      override def nullValue: List[SchemaType] = ???
    }
    implicit val exampleValueCodec: JsonValueCodec[ExampleValue] = new JsonValueCodec {
      override def decodeValue(in: JsonReader, default: ExampleValue): ExampleValue = ???

      override def encodeValue(x: ExampleValue, out: JsonWriter): Unit = x match {
        case s: ExampleSingleValue => out.writeVal(s.value.toString)
        case m: ExampleMultipleValue => m.values.foreach(a => out.writeVal(a.toString))
      }

      override def nullValue: ExampleValue = ???
    }
    JsonCodecMaker.make[Schema](
      CodecMakerConfig.withDiscriminatorFieldName(None)
        .withEncodingOnly(true)
        .withInlineOneValueClasses(true),
    )
  }

  private val arraySchema: Schema = Schema(SchemaType.Array)
  private val booleanSchema: Schema = Schema(SchemaType.Boolean)
  private val integerSchema: Schema = Schema(SchemaType.Integer)
  private val numberSchema: Schema = Schema(SchemaType.Number)
  private val objectSchema: Schema = Schema(SchemaType.Object)
  private val stringSchema: Schema = Schema(SchemaType.String)
}

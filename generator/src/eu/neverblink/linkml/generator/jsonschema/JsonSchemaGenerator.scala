package eu.neverblink.linkml.generator.jsonschema

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml
import eu.neverblink.linkml.metamodel.Anything
import eu.neverblink.linkml.schemaview
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
    cls.cls.alias match {
      case Some(a) => a
      case _ => Case.PascalCase(cls.cls.name)
    }

  /** Translate a slot name into a JSON Schema form, respecting aliases and LinkML casing rules
    */
  protected def slotName(slot: SlotView): MappedSlotName =
    slot.slot.alias match {
      case Some(a) => a
      case _ => Case.deSpaceCase(slot.slot.name)
    }

  /** Generate a Schema for a specific slot, which maps to a JSON Schema property.
    * @param attributeView
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
      attributeView: AttributeView,
      needKeyless: mutable.Set[(MappedClassName, MappedSlotName)],
      needValue: mutable.Set[(MappedClassName, MappedSlotName)],
  ): (MappedSlotName, Schema) = {
    val slot = attributeView.slotView
    val slotSchema = attributeView match {
      case _: AnyView => Schema.Empty
      case ClassInlineAttributeView(_, _, classView, inlineType) =>
        val mappedClassName = className(classView)
        inlineType match {
          case InlineType.plain =>
            new Schema(
              $ref = new Some("#/$defs/".concat(mappedClassName)),
            )
          case InlineType.optional =>
            new Schema(
              $ref = new Some("#/$defs/".concat(mappedClassName)),
            ) // TODO LNK-34: or null
          case InlineType.list =>
            new Schema(
              $ref = new Some("#/$defs/".concat(mappedClassName)),
            ).arrayOf // TODO LNK-34: or null
          case InlineType.dict(CollectionForm.CompactDict(key)) =>
            needKeyless.add((mappedClassName, slotName(classView.derivedAttributes(key))))
            new Schema(
              $ref = new Some("#/$defs/" + mappedClassName + "__identifier_optional"),
            ).dictOf // TODO LNK-34: or null
          case InlineType.dict(CollectionForm.SimpleDict(key, value)) =>
            needValue.add((mappedClassName, slotName(classView.derivedAttributes(value))))
            new Schema(
              $ref = new Some("#/$defs/" + mappedClassName + "__simple_dict_value"),
            ).dictOf // TODO LNK-34: or null
        }
      case ClassReferenceAttributeView(slotView, _, classView, identifierView) =>
        typeToRuntime(identifierView.typeView)
          .copy(
            $comment = Some(s"Reference to ${classView.name} class"),
            minimum = toBigDecimalOpt(identifierView.minimumValue),
            maximum = toBigDecimalOpt(identifierView.maximumValue),
            pattern = identifierView.pattern.map(Pattern(_)),
          )
          .arrayOfIf(slotView.slot.multivalued)
      case typeAttribute: TypeAttributeView =>
        typeToRuntime(typeAttribute.typeView)
          .copy(
            minimum = toBigDecimalOpt(typeAttribute.minimumValue),
            maximum = toBigDecimalOpt(typeAttribute.maximumValue),
            pattern = typeAttribute.pattern.map(Pattern(_)),
          )
          .arrayOfIf(typeAttribute.slotView.slot.multivalued)
      case EnumAttributeView(slotView, _, enumView) =>
        new Schema(
          $ref = new Some("#/$defs/".concat(enumView._enum.name)),
        ).arrayOfIf(slotView.slot.multivalued)
    }
    slotName(slot) -> slotSchema.copy(
      title = slot.slot.title,
      description = slot.slot.description,
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
    // If a tree root is defined, only include classes reachable from the tree root (pruning).
    // Otherwise, include all classes in the schema view.
    val query = maybeTreeRoot match {
      case Some(root) => sv.derivedReachabilityQuery(Seq(root), true, false)
      case _ => IncludeAllReachabilityQuery()
    }
    val needKeyless = mutable.Set.empty[(MappedClassName, MappedSlotName)]
    val needValue = mutable.Set.empty[(MappedClassName, MappedSlotName)]
    val defsClasses = for cls <- sv.classes.values if query.reachable(cls) yield {
      val attributes = cls.attributeViews
      val properties = new mutable.LinkedHashMap[MappedClassName, Schema]
      properties.sizeHint(attributes.values.size)
      for attribute <- attributes.values do {
        properties.addOne(generateSlotSchema(attribute, needKeyless, needValue))
      }
      val requiredSlots = attributes.values.foldLeft(new mutable.ListBuffer[String]) { (acc, s) =>
        if (s.slotView.slot.required) acc.addOne(s.slotView.name)
        else acc
      }.toList
      (
        className(cls),
        objectSchema.copy(
          required = requiredSlots,
          properties = immutable.ListMap.newBuilder.addAll(properties).result(),
          additionalProperties = new Some(if (open) AnySchema.Anything else AnySchema.Nothing),
          title = cls.cls.title,
          description = cls.cls.description,
        ),
      )
    }
    val defsEnums = for ev <- sv.enums.values yield {
      val enum_ = ev._enum
      val enumValues = enum_.permissibleValues.keys.foldLeft(new mutable.ListBuffer[ExampleValue]) {
        (acc, v) => acc.addOne(ExampleSingleValue(v))
      }.toList
      (
        enum_.name,
        objectSchema.copy(
          `type` = new Some(List(SchemaType.String)),
          `enum` = new Some(enumValues),
          title = enum_.title,
          description = enum_.description,
        ),
      )
    }
    val baseSchema = maybeTreeRoot match {
      case Some(treeRoot) =>
        val classSchema =
          new Schema(
            $ref = new Some("#/$defs/".concat(className(treeRoot))),
          )
        val inlineType = treeRoot.treeRootInlineType(treeRootInlineTypeOverride)
        inlineType match {
          case InlineType.plain => classSchema // object (mandatory)
          case InlineType.optional =>
            Schema.oneOf(List(classSchema, Schema.Null), discriminator = None) // object or null
          case InlineType.list =>
            arraySchema.copy(items = Some(classSchema)) // array of objects
          case InlineType.dict(CollectionForm.CompactDict(key)) =>
            val mappedClassName = className(treeRoot)
            needKeyless.add((mappedClassName, slotName(treeRoot.derivedAttributes(key))))
            new Schema(
              $ref = new Some("#/$defs/" + mappedClassName + "__identifier_optional"),
            ).dictOf
          case InlineType.dict(CollectionForm.SimpleDict(key, value)) =>
            val mappedClassName = className(treeRoot)
            needValue.add((mappedClassName, slotName(treeRoot.derivedAttributes(value))))
            new Schema(
              $ref = new Some("#/$defs/" + mappedClassName + "__simple_dict_value"),
            ).dictOf
        }
      case _ => Schema.Empty
    }
    // Generate the needed keyless/value refs
    val defMap = defsClasses.toMap
    val defsKeyless = for (className, idField) <- needKeyless yield {
      val classSchema = defMap(className)
      (
        className.concat("__identifier_optional"),
        classSchema.copy(required = classSchema.required.filter(_ != idField)),
      )
    }
    val defsValues = for (className, valueField) <- needValue yield {
      val simpleDict = defMap(className)
      (
        className.concat("__simple_dict_value"),
        simpleDict.properties(valueField).asInstanceOf[Schema],
      )
    }
    val defs = immutable.ListMap.newBuilder[String, SchemaLike].addAll({
      val m = new collection.mutable.LinkedHashMap[String, SchemaLike]
      m.sizeHint(defsClasses.size + defsEnums.size + defsKeyless.size + defsValues.size)
      defsClasses.foreach(kv => m.update(kv._1, kv._2))
      defsEnums.foreach(kv => m.update(kv._1, kv._2))
      defsKeyless.foreach(kv => m.update(kv._1, kv._2))
      defsValues.foreach(kv => m.update(kv._1, kv._2))
      m
    }).result()
    baseSchema.copy(
      $schema = new Some("https://json-schema.org/draft/2020-12/schema"),
      $id = new Some(sv.root.id.uri(using sv.rootPrefixResolver)),
      title = sv.root.title.orElse(new Some(sv.root.name)),
      description = sv.root.description,
      $defs = new Some(defs),
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
      treeRootInlineTypeOverride: Option[String] = None,
  ): String =
    writeToString(
      generate(open, treeRootOverride, treeRootInlineTypeOverride),
      WriterConfig.withIndentionStep(indentationStep),
    )
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
    case DateType => stringSchema.copy(format = new Some(SchemaFormat.Date))
    case DateTimeType => stringSchema.copy(format = new Some(SchemaFormat.DateTime))
    case TimeType => stringSchema.copy(format = new Some("time"))
    case UriOrCurieType =>
      Schema.Empty.copy(anyOf =
        List(
          stringSchema.copy(format = new Some("uri")),
          stringSchema.copy(format = new Some("curie")),
        ),
      )
    case UriType => stringSchema.copy(format = new Some("uri"))
    case CurieType => stringSchema.copy(format = new Some("curie"))
    case NcNameType => stringSchema.copy(format = new Some("ncname"))
    case UnknownType => Schema.Empty
  }

  type MappedClassName = String
  type MappedSlotName = String

  extension (schema: Schema)
    /** Wrap this Schema in an array
      */
    def arrayOf: Schema = arraySchema.copy(items = new Some(schema))

    /** Wrap this Schema in an array if the condition is true, return the schema unchanged otherwise
      */
    def arrayOfIf(condition: Boolean): Schema = if condition then schema.arrayOf else schema

    /** Wrap this Schema as a dict (object with additional properties set to this schema)
      */
    def dictOf: Schema = objectSchema.copy(additionalProperties = new Some(schema))

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

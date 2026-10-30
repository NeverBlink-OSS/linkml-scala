package eu.neverblink.linkml.generator.translation

import eu.neverblink.linkml.generator.options.TranslationGeneratorOptions
import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter,
  WriterConfig,
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.generator.JsonDocumentGenerator
import eu.neverblink.linkml.generator.docs.DocsRenamer
import eu.neverblink.linkml.generator.erdiagram.ErDiagramRenamer
import eu.neverblink.linkml.generator.frictionless.FrictionlessRenamer
import eu.neverblink.linkml.generator.graphql.GraphQlRenamer
import eu.neverblink.linkml.generator.jsonschema.JsonRenamer
import eu.neverblink.linkml.generator.ossie.OssieRenamer
import eu.neverblink.linkml.generator.pydantic.PydanticRenamer
import eu.neverblink.linkml.generator.scala.ScalaRenamer
import eu.neverblink.linkml.generator.translation.TranslationGenerator.Translation
import eu.neverblink.linkml.generator.typescript.TypeScriptRenamer
import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.schemaview.*
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}

import scala.collection.immutable.ListMap

class TranslationGenerator(using sv: SchemaView)
    extends JsonDocumentGenerator[TranslationGeneratorOptions, Translation] {
  override final def generate(options: TranslationGeneratorOptions): Translation = {
    val renamer = resolveRenames(Case.base(options.target).filter(_ != '_'))
    Translation(
      sv.classes.values.map(el => el.name -> renamer.className(el)).toMap,
      sv.classes.values.map { el =>
        val attributes: Iterable[(String, SlotDefinition)] =
          if options.derivedAttributes then el.derivedAttributes.view.mapValues(_.slot)
          else el.cls.attributes
        el.name -> attributes.map { (name, attr) =>
          name -> renamer.classAttributeName(el, attr)
        }.toMap
      }.toMap,
      sv.types.values.map(el => el.name -> renamer.typeName(el)).toMap,
      sv.enums.values.map(el => el.name -> renamer.enumName(el)).toMap,
      sv.slotDefinitions.values.map(el => el.name -> renamer.slotName(el)).toMap,
      sv.enums.values.map { el =>
        el.name -> el.derivedValues.map { pvv =>
          pvv.pv.text -> renamer.permissibleValueName(el, pvv.pv)
        }.toMap
      }.toMap,
      sv.functions.values.map(el => el.name -> renamer.functionName(el)).toMap,
    )
  }

  override protected def codec: JsonValueCodec[Translation] =
    JsonCodecMaker.make[Translation](CodecMakerConfig.withEncodingOnly(true))

  override protected def writerConfig(options: TranslationGeneratorOptions): WriterConfig =
    WriterConfig.withIndentionStep(options.indentationStep)

  override protected def defaultOptions: TranslationGeneratorOptions =
    TranslationGeneratorOptions()

  def resolveRenames(id: String): Renamer = TranslationGenerator.resolveRenames(id)
}

object TranslationGenerator {

  /** Maps each value of [[Options.to]] to the renamer it selects. */
  val renamers: ListMap[String, Renamer] = ListMap(
    "base" -> BaseRenamer,
    "uri" -> UriRenamer,
    "scala" -> ScalaRenamer,
    "graphql" -> GraphQlRenamer,
    "frictionless" -> FrictionlessRenamer,
    "ossie" -> OssieRenamer,
    "erdiagram" -> ErDiagramRenamer,
    "json" -> JsonRenamer,
    "typescript" -> TypeScriptRenamer,
    "pydantic" -> PydanticRenamer,
    "docs" -> DocsRenamer,
  )

  /** The names of the translation targets. */
  val targets: Seq[String] = renamers.keys.toSeq

  def resolveRenames(id: String): Renamer =
    renamers.getOrElse(id, throw IllegalArgumentException(s"Unknown translation target: '$id'"))

  final case class Translation(
      classes: Map[String, String],
      classAttributes: Map[String, Map[String, String]],
      types: Map[String, String],
      enums: Map[String, String],
      slots: Map[String, String],
      permissibleValues: Map[String, Map[String, String]],
      functions: Map[String, String],
  )

  object UriRenamer extends Renamer {
    def className(el: ClassView): String = el.uriStr

    override def classAttributeName(el: ClassView, attr: SlotDefinition): String =
      el.derivedAttributes(attr.name).uriStr

    def enumName(el: EnumView): String = el.uriStr

    def permissibleValueName(
        el: EnumView,
        pv: PermissibleValue,
    ): String = PermissibleValueView(pv, el).uriStr

    def slotName(el: SlotView): String = el.uriStr

    def typeName(el: TypeView): String = el.uriStr

    def functionName(el: FunctionView): String = el.uriStr
  }

  object BaseRenamer extends Renamer {
    override def className(el: ClassView): String = el.baseName

    override def classAttributeName(el: ClassView, attr: SlotDefinition): String =
      el.derivedAttributes(attr.name).baseName

    override def slotName(el: SlotView): String = el.baseName

    override def typeName(el: TypeView): String = el.baseName

    override def enumName(el: EnumView): String = el.baseName

    override def permissibleValueName(el: EnumView, pv: PermissibleValue): String =
      Case.base(pv.text)

    override def functionName(el: FunctionView): String = el.baseName
  }
}

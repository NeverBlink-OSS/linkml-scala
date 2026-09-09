package eu.neverblink.linkml.generator.translation

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter,
  WriterConfig,
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.generator.JsonDocumentGenerator
import eu.neverblink.linkml.generator.frictionless.FrictionlessRenamer
import eu.neverblink.linkml.generator.graphql.GraphQlRenamer
import eu.neverblink.linkml.generator.scala.ScalaRenamer
import eu.neverblink.linkml.generator.translation.TranslationGenerator.Translation
import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.schemaview.*
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}

class TranslationGenerator(using sv: SchemaView)
    extends JsonDocumentGenerator[TranslationGenerator.Options, Translation] {
  override final def generate(options: TranslationGenerator.Options): Translation = {
    val renamer = resolveRenames(Case.base(options.to).filter(_ != '_'))
    Translation(
      sv.classes.values.map(el => el.name -> renamer.className(el)).toMap,
      sv.classes.values.map { el =>
        el.name -> el.cls.attributes.values.map { attr =>
          attr.name -> renamer.classAttributeName(el, attr)
        }.toMap
      }.toMap,
      sv.types.values.map(el => el.name -> renamer.typeName(el)).toMap,
      sv.enums.values.map(el => el.name -> renamer.enumName(el)).toMap,
      sv.slotDefinitions.values.map(el => el.name -> renamer.slotName(el)).toMap,
      sv.enums.values.map { el =>
        el.name -> el.derivedValues.map { (pv, _) =>
          pv.text -> renamer.permissibleValueName(el, pv)
        }.toMap
      }.toMap,
    )
  }

  override protected def codec: JsonValueCodec[Translation] =
    JsonCodecMaker.make[Translation](CodecMakerConfig.withEncodingOnly(true))

  override protected def writerConfig(options: TranslationGenerator.Options): WriterConfig =
    WriterConfig.withIndentionStep(options.indentationStep)

  override protected def defaultOptions: TranslationGenerator.Options =
    TranslationGenerator.Options()

  def resolveRenames(id: String): Renamer = id match {
    case "base" => TranslationGenerator.BaseRenamer
    case "uri" => TranslationGenerator.UriRenamer
    case "scala" => ScalaRenamer
    case "graphql" => GraphQlRenamer
    case "frictionless" => FrictionlessRenamer
    case other => throw IllegalArgumentException(s"Unknown translation target: '$other'")
  }
}

object TranslationGenerator {
  val availableValues = """"base", "uri", "scala", "graphql", "frictionless""""
  
  final case class Translation(
      classes: Map[String, String],
      classAttributes: Map[String, Map[String, String]],
      types: Map[String, String],
      enums: Map[String, String],
      slots: Map[String, String],
      permissibleValues: Map[String, Map[String, String]],
  )

  final case class Options(to: String = "base", indentationStep: Int = 2)

  object UriRenamer extends Renamer {
    def className(el: ClassView): String = el.uriStr

    override def classAttributeName(el: ClassView, attr: SlotDefinition): String =
      el.derivedAttributes(attr.name).uriStr

    def enumName(el: EnumView): String = el.uriStr

    def permissibleValueName(
        el: EnumView,
        pv: PermissibleValue,
    ): String = el.toMeaning(pv.text).uri(using el.definingPrefixResolver)

    def slotName(el: SlotView): String = el.uriStr

    def typeName(el: TypeView): String = el.uriStr
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
  }
}

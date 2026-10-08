package eu.neverblink.linkml.generator.translation

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter,
  WriterConfig,
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.generator.JsonDocumentGenerator
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
    extends JsonDocumentGenerator[TranslationGenerator.Options, Translation] {
  override final def generate(options: TranslationGenerator.Options): Translation = {
    val renamer = resolveRenames(Case.base(options.to).filter(_ != '_'))
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
    )
  }

  override protected def codec: JsonValueCodec[Translation] =
    JsonCodecMaker.make[Translation](CodecMakerConfig.withEncodingOnly(true))

  override protected def writerConfig(options: TranslationGenerator.Options): WriterConfig =
    WriterConfig.withIndentionStep(options.indentationStep)

  override protected def defaultOptions: TranslationGenerator.Options =
    TranslationGenerator.Options()

  def resolveRenames(id: String): Renamer = TranslationGenerator.resolveRenames(id)
}

object TranslationGenerator {

  /** Every translation target: the name [[Options.to]] takes, and the renamer that names it. */
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
  )

  /** Options for [[TranslationGenerator]].
    *
    * @param to
    *   The framework whose names the dictionaries translate to. One of "base", "uri", "scala",
    *   "graphql", "frictionless", "ossie", "erdiagram", "json", "typescript" or "pydantic".
    *
    * @param indentationStep
    *   Indentation of the JSON output.
    *
    * @param derivedAttributes
    *   Whether `classAttributes` lists every slot a class has: its own attributes, the slots it
    *   names in `slots`, and those it inherits or gets from mixins. By default it lists only the
    *   class's own `attributes`.
    */
  final case class Options(
      to: String = "base",
      indentationStep: Int = 2,
      derivedAttributes: Boolean = false,
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

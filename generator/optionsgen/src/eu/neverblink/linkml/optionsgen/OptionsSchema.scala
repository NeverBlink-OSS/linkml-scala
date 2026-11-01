package eu.neverblink.linkml.optionsgen

import eu.neverblink.linkml.generator.scala.ScalaRenamer
import eu.neverblink.linkml.schemaview.*

/** The generator options of `model/converter/all_generators.yaml`, as the generated code needs
  * them.
  */
final class OptionsSchema(using sv: SchemaView) {

  /** The generators, as the functions of the schema. */
  val functions: Seq[GeneratorFunction] =
    sv.functions.values.toSeq.sortBy(fv => sv.elementOrder(fv.function)).map(GeneratorFunction(_))

  /** The options class of each generator, in the order of the classes in the schema. */
  val generators: Seq[OptionsClass] =
    functions.map(_.options).sortBy(c => sv.elementOrder(c.cv.cls))

  /** Every enum of the schema. */
  val enums: Seq[EnumView] = sv.enums.values.toSeq.sortBy(_.name)

  def enumName(ev: EnumView): String = ScalaRenamer.enumName(ev)

  /** Each value of [[ev]]: its Scala case, its name in the schema and its aliases. */
  def enumCases(ev: EnumView): Seq[(scalaCase: String, name: String, aliases: Seq[String])] =
    ev.derivedValues.map { v =>
      (
        s"${enumName(ev)}.${ScalaRenamer.permissibleValueName(ev, v.pv)}",
        v.pv.text,
        v.pv.aliases.map(_.plain),
      )
    }
}

/** One generator, as a function of the schema. */
final case class GeneratorFunction(fv: FunctionView) {

  /** The name in the schema, such as `json_schema`. */
  val name: String = fv.baseName

  /** The Scala name, such as `jsonSchema`. */
  val scalaName: String = ScalaRenamer.functionName(fv)

  /** The exported C function. */
  val symbol: String = s"linkml_$name"

  val description: String = Text.oneLine(fv.function.description.fold("")(_.plain))

  val options: OptionsClass = OptionsClass(fv.parameters.get)

  /** What it returns: a document, or else JSON with the extra data's type if it is a class with
    * only that, such as `GeneratedFiles`.
    */
  val returns: Option[Option[TypeView]] = fv.returns match {
    case Some(_: TypeView) => None
    case Some(cv: ClassView) =>
      Some(
        cv.cls.extraSlots.flatMap(_.rangeExpression).flatMap(_.range)
          .flatMap(r => fv.sv.types.get(r.value)).filter(_ => cv.derivedAttributes.isEmpty),
      )
    case other => sys.error(s"Function $name returns an unsupported element: $other")
  }
}

/** The options of one generator. */
final case class OptionsClass(cv: ClassView) {

  /** `JsonSchemaGeneratorOptions` */
  val scalaName: String = ScalaRenamer.className(cv)

  /** `JsonSchema` */
  val baseName: String = scalaName.stripSuffix("GeneratorOptions")

  val fields: Seq[OptionField] = cv.sortedAttributeViews.map(OptionField(_))
}

/** One option of a generator. */
final case class OptionField(attribute: AttributeView) {
  private val slot = attribute.slotView

  /** The name in the schema, such as `metadata_language`. */
  val name: String = slot.name

  /** The Scala name, such as `metadataLanguage`. */
  val scalaName: String = ScalaRenamer.slotName(slot)

  val description: String = Text.oneLine(slot.slot.description.fold("")(_.plain))

  val required: Boolean = slot.slot.required

  /** The Scala type of the value, without the `Option` of an optional one. */
  val valueType: String = attribute match {
    case TypeAttributeView(_, _, tv) =>
      tv.runtimeType match {
        case StringType => "String"
        case IntegerType => "Int"
        case BooleanType => "Boolean"
        case other => sys.error(s"Option $name has an unsupported type: $other")
      }
    case EnumAttributeView(_, _, ev) => ScalaRenamer.enumName(ev)
    case other => sys.error(s"Option $name has an unsupported range: $other")
  }

  val isEnum: Boolean = attribute.isInstanceOf[EnumAttributeView]

  /** The names of the values, if the option is an enum. */
  val enumValues: Option[Seq[String]] = attribute match {
    case EnumAttributeView(_, _, ev) => Some(ev.derivedValues.map(_.pv.text))
    case _ => None
  }

  val scalaType: String = if required then valueType else s"Option[$valueType]"

  /** The default as a Scala expression, and as the user writes it. None if there is none. */
  val default: Option[(scala: String, text: String)] =
    if !required then None
    else
      attribute match {
        case EnumAttributeView(_, _, ev) =>
          slot.ifAbsent(ev).map { pv =>
            (s"${ScalaRenamer.enumName(ev)}.${ScalaRenamer.permissibleValueName(ev, pv)}", pv.text)
          }
        case TypeAttributeView(_, _, tv) =>
          slot.ifAbsent(tv).map {
            case s: String => (Literals.string(s), s)
            case v => (v.toString, v.toString)
          }
        case _ => None
      }

  /** The default value in Scala, `None` for an optional option. */
  def scalaDefault: Option[String] = if required then default.map(_.scala) else Some("None")
}

object Text {

  /** The text with its lines joined. */
  def oneLine(text: String): String = text.split("\\s+").filter(_.nonEmpty).mkString(" ")
}

object Literals {

  /** A Scala string literal. */
  def string(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

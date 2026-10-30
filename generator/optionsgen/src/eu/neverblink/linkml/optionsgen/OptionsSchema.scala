package eu.neverblink.linkml.optionsgen

import eu.neverblink.linkml.generator.scala.ScalaRenamer
import eu.neverblink.linkml.schemaview.*

/** The generator options of `model/generator-options.yaml`, as the generated code needs them. */
final class OptionsSchema(using sv: SchemaView) {

  /** The options class of each generator: every class in the schema that is not a mixin. */
  val generators: Seq[OptionsClass] =
    sv.sortedClasses.filter(c => c.isConcrete && (c.definingSchema eq sv.root)).map(OptionsClass(_))

  /** Every enum of the schema. */
  val enums: Seq[EnumView] =
    sv.enums.values.filter(_.definingSchema eq sv.root).toSeq.sortBy(_.name)

  /** The options of [[generator]], such as `JsonSchemaGenerator`. */
  def options(generator: String): OptionsClass =
    generators.find(_.scalaName == s"${generator}Options").getOrElse(
      sys.error(s"No ${generator}Options class in the options schema"),
    )

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

  val description: String =
    slot.slot.description.fold("")(_.plain).split("\\s+").filter(_.nonEmpty).mkString(" ")

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
      (attribute, slot.slot.ifabsent.map(_.trim)) match {
        case (EnumAttributeView(_, _, ev), Some(_)) =>
          slot.ifAbsent(ev).map { pv =>
            (s"${ScalaRenamer.enumName(ev)}.${ScalaRenamer.permissibleValueName(ev, pv)}", pv.text)
          }
        case (_, Some(OptionField.stringConstructor(value))) =>
          val text = value.stripPrefix("\"").stripSuffix("\"")
          Some((Literals.string(text), text))
        case (_, Some(OptionField.intConstructor(value))) => Some((value, value))
        case (_, Some(value)) if valueType == "Boolean" =>
          Some((value.toLowerCase, value.toLowerCase))
        case _ => None
      }

  /** The default value in Scala, `None` for an optional option. */
  def scalaDefault: Option[String] = if required then default.map(_.scala) else Some("None")
}

private object OptionField {
  val stringConstructor = """^string\((.*)\)$""".r
  val intConstructor = """^int\((-?\d+)\)$""".r
}

object Literals {

  /** A Scala string literal. */
  def string(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

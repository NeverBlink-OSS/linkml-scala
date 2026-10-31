package eu.neverblink.linkml.optionsgen

import eu.neverblink.linkml.generator.scala.ScalaRenamer
import eu.neverblink.linkml.schemaview.*

/** The generators and importers of `model/converter/all_converters.yaml`, as the generated code
  * needs them.
  */
final class OptionsSchema(using sv: SchemaView) {

  /** The generators and the importers, as the functions of the schema. */
  val functions: Seq[ConverterFunction] =
    sv.functions.values.toSeq.sortBy(fv => sv.elementOrder(fv.function)).map(ConverterFunction(_))

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

/** One generator or importer, as a function of the schema. */
final case class ConverterFunction(fv: FunctionView) {

  /** The name in the schema, such as `json_schema`. */
  val name: String = fv.baseName

  /** Whether it is an importer, such as `from_owl`. An importer takes the document to import, and
    * no schema.
    */
  val isImporter: Boolean = name.startsWith("from_")

  /** The Scala name, such as `jsonSchema`. */
  val scalaName: String = ScalaRenamer.functionName(fv)

  /** The exported C function. */
  val symbol: String = s"linkml_$name"

  val description: String = Text.oneLine(fv.function.description.fold("")(_.plain))

  val options: OptionsClass = OptionsClass(fv.parameters.get)

  val returns: Returns = fv.returns match {
    case Some(_: TypeView) => Returns.Document
    case Some(cv: ClassView) if cv.isAny => Returns.Any
    case Some(cv: ClassView) if cv.derivedAttributes.nonEmpty => Returns.Object
    case Some(cv: ClassView) =>
      cv.cls.extraSlots.flatMap(_.rangeExpression).flatMap(_.range)
        .flatMap(r => fv.sv.types.get(r.value))
        .fold(Returns.Any)(Returns.Map(_))
    case other => sys.error(s"Function $name returns an unsupported element: $other")
  }
}

/** What a function returns. All but a document are JSON. */
enum Returns {
  case Document

  /** An object with the slots of a class, such as `OwlImportResult`. */
  case Object

  /** An object with values of one type, such as `Files`. */
  case Map(value: TypeView)
  case Any
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

  val multivalued: Boolean = slot.slot.multivalued

  /** The class of the value, if it is an object, such as a config. */
  val valueClass: Option[ClassView] = attribute match {
    case a: ClassAttributeView => Some(a.classView)
    case _ => None
  }

  /** The Scala type of the value, without the `Option` of an optional one. */
  val valueType: String = attribute match {
    case TypeAttributeView(_, _, tv) if !multivalued =>
      tv.runtimeType match {
        case StringType => "String"
        case IntegerType => "Int"
        case BooleanType => "Boolean"
        case other => sys.error(s"Option $name has an unsupported type: $other")
      }
    case EnumAttributeView(_, _, ev) if !multivalued => ScalaRenamer.enumName(ev)
    case a: ClassAttributeView => ScalaRenamer.className(a.classView)
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

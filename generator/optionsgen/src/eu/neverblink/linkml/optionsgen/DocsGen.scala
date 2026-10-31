package eu.neverblink.linkml.optionsgen

import eu.neverblink.linkml.runtime.LocalizedText
import eu.neverblink.linkml.schemaview.*

/** The data of the generator and importer pages of the docs site, which its components render. */
object DocsGen {

  def apply(schema: OptionsSchema): String = {
    val pages = schema.functions.map { f =>
      val options = f.options.fields.map { field =>
        val option = attribute(field.attribute)
        option("cli") = if field.onCli then ujson.Str(field.cliFlag) else ujson.Null
        option("scala") = field.scalaName
        option
      }
      ujson.Obj(
        "name" -> f.name,
        "kind" -> (if f.isImporter then "importer" else "generator"),
        "page" -> page(f),
        "playground" -> playground(f),
        "description" -> f.description,
        "snippets" -> Snippets(f, schema).map { s =>
          ujson.Obj(
            "sdk" -> s.sdk.toString,
            "label" -> s.sdk.label,
            "lang" -> s.sdk.lang,
            "code" -> s.code,
          )
        },
        "options" -> options,
        "classes" -> classes(f.options.cv).map(_.name),
      )
    }
    val classViews = schema.functions.flatMap(f => classes(f.options.cv)).distinctBy(_.name)
    val json = ujson.Obj(
      "functions" -> pages,
      "classes" -> ujson.Obj.from(classViews.map { cv =>
        cv.name -> ujson.Obj(
          "description" -> describe(cv.cls.description),
          "attributes" -> cv.sortedAttributeViews.map(attribute),
        )
      }),
      "enums" -> ujson.Obj.from(schema.enums.map { ev =>
        ev.name -> ujson.Obj(
          "description" -> describe(ev._enum.description),
          "values" -> ev.derivedValues.map(v =>
            ujson.Obj("name" -> v.pv.text, "description" -> describe(v.pv.description)),
          ),
        )
      }),
    )
    ujson.write(json, indent = 2) + "\n"
  }

  /** The path of a converter's page under `website/src/content/docs`, without the extension. */
  def page(f: ConverterFunction): String =
    if f.isImporter then s"importers/${f.name.stripPrefix("from_").replace('_', '-')}"
    else s"generators/${f.name.replace('_', '-')}"

  /** The playground, with PROV-O and the converter open, as in the examples. */
  private def playground(f: ConverterFunction): String = {
    val step =
      if f.isImporter then s"input=${f.name.stripPrefix("from_")}" else s"target=${f.scalaName}"
    s"https://linkml.ohi.dev/playground/?ohi=w3c/prov-o@2013&$step"
  }

  /** The classes an options class takes as values, and the classes they take, and so on. */
  private def classes(cv: ClassView): Seq[ClassView] = {
    def from(cv: ClassView, seen: Seq[ClassView]): Seq[ClassView] =
      cv.sortedAttributeViews.foldLeft(seen) {
        case (seen, a: ClassAttributeView) if !a.classView.isAny && !seen.contains(a.classView) =>
          from(a.classView, seen :+ a.classView)
        case (seen, _) => seen
      }
    from(cv, Nil)
  }

  private def attribute(a: AttributeView): ujson.Obj = {
    val slot = a.slotView.slot
    val (kind, name) = a match {
      case TypeAttributeView(_, _, tv) => ("type", tv.name)
      case EnumAttributeView(_, _, ev) => ("enum", ev.name)
      case c: ClassAttributeView => ("class", c.classView.name)
      case _: AnyView => ("any", "Any")
    }
    val collection = a match {
      case ClassInlineAttributeView(_, _, _, InlineType.dict(_)) => ujson.Str("map")
      case _ if slot.multivalued => ujson.Str("list")
      case _ => ujson.Null
    }
    val default = if !slot.required then None
    else
      a match {
        case EnumAttributeView(slotView, _, ev) => slotView.ifAbsent(ev).map(_.text)
        case TypeAttributeView(slotView, _, tv) => slotView.ifAbsent(tv).map(_.toString)
        case _ => None
      }
    ujson.Obj(
      "name" -> slot.name,
      "type" -> ujson.Obj("kind" -> kind, "name" -> name, "collection" -> collection),
      "required" -> (slot.required && default.isEmpty),
      "default" -> default.fold(ujson.Null)(ujson.Str(_)),
      "description" -> describe(slot.description),
    )
  }

  private def describe(text: Option[LocalizedText]): String = Text.oneLine(text.fold("")(_.plain))
}

package eu.neverblink.linkml.generator.rdfs

import eu.neverblink.linkml.generator.RdfGeneratorBase
import eu.neverblink.linkml.generator.RdfGeneratorBase.{RdfFormat, RdfOptions}
import eu.neverblink.linkml.rdf.*
import eu.neverblink.linkml.metamodel.{CommonMetadata, PermissibleValue}
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.{LocalizedText, Reference}
import eu.neverblink.linkml.schemaview.{
  ClassView,
  ElementView,
  EnumView,
  LocalizedTextType,
  SchemaView,
  SlotView,
  TypeView,
}

import scala.collection.mutable

class RdfsGenerator(using sv: SchemaView) extends RdfGeneratorBase[RdfsGenerator.Options] {

  override protected def defaultOptions: RdfsGenerator.Options = RdfsGenerator.Options()

  /** Emit the RDFS metadata of every definition describing the same subject, skipping titles,
    * descriptions and links repeated between them.
    *
    * @param definitions
    *   The definitions describing the subject
    * @param metadataOf
    *   Get the metadata of a definition
    */
  private def emitCommonMetadata[D](
      sink: RdfSink,
      subject: Resource,
      definitions: collection.Seq[D],
  )(metadataOf: D => CommonMetadata): Unit = {
    // Text without a language is in the element's language, or the schema's.
    def language(cm: CommonMetadata) = cm.inLanguage.orElseFast(sv.root.inLanguage)
    val titles = new java.util.HashSet[(LocalizedText, Option[String])]
    definitions.foreach { d =>
      val cm = metadataOf(d)
      cm.title.foreachFast { t =>
        val l = language(cm)
        if (titles.add((t, l))) langStringProperty(sink, subject, Rdfs.label, t, l)
      }
    }
    val descriptions = new java.util.HashSet[(LocalizedText, Option[String])]
    definitions.foreach { d =>
      val cm = metadataOf(d)
      cm.description.foreachFast { t =>
        val l = language(cm)
        if (descriptions.add((t, l))) langStringProperty(sink, subject, Rdfs.comment, t, l)
      }
    }
    val links = new java.util.HashSet[String]
    definitions.foreach { d =>
      metadataOf(d).seeAlso.foreach { x =>
        val uri = x.original
        if (links.add(uri)) sink.triple(subject, Rdfs.seeAlso, Iri(uri))
      }
    }
  }

  /** The `rdfs:range` for the range of a slot. A `LocalizedText` type, such as `langstring`, is
    * `rdf:PlainLiteral`, which has both plain strings and texts in a language.
    */
  private def rangeIri(range: ElementView[?, ?]): Iri = range match {
    case tv: TypeView if tv.runtimeType eq LocalizedTextType => RdfsGenerator.plainLiteral
    case _ => Iri(range.uriStr)
  }

  /** Emit a single RDFS property covering all the usages of one slot URI: the declaration and the
    * metadata once, a domain per class using it, and each distinct range it derives to.
    *
    * @param usages
    *   All derived slots sharing this property URI, in class iteration order, or the slot itself
    *   when no class uses it.
    */
  private def emitProperty(
      sink: RdfSink,
      propertyNameIri: Iri,
      usages: Seq[SlotView],
  ): Unit = {
    sink.triple(propertyNameIri, Rdf.`type`, Rdf.Property)
    emitCommonMetadata(sink, propertyNameIri, usages)(_.slot)

    sv.lowestCommonAncestors(
      usages
        .flatMap(_.slot.domain)
        .flatMap(_.asInstanceOf[Reference[ClassView]].resolve),
    ).map(u => Iri(u.uriStr)).distinct.foreach { domain =>
      sink.triple(propertyNameIri, Rdfs.domain, domain)
    }

    usages.flatMap(_.derivedRange.resolve.toList).map(rangeIri).distinct.foreach { range =>
      sink.triple(propertyNameIri, Rdfs.range, range)
    }

    usages.flatMap(_.parents).map(_.uriStr).distinct.foreach { parentUriStr =>
      sink.triple(propertyNameIri, Rdfs.subPropertyOf, Iri(parentUriStr))
    }
  }

  /** Generates RDF Schema and pushes the namespaces and triples into the provided [[RdfSink]].
    * @param sink
    *   The sink that receives namespace declarations and triples.
    * @param options
    *   What to generate. See [[RdfsGenerator.Options]].
    */
  override final def generate(
      sink: RdfSink,
      options: RdfsGenerator.Options,
  ): Unit = {
    import options.onlyClassesFromRootSchema
    addNamespaces(
      sink,
      Array(
        ("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
        ("rdfs", "http://www.w3.org/2000/01/rdf-schema#"),
        ("xsd", "http://www.w3.org/2001/XMLSchema#"),
      ),
    )

    val classes =
      if onlyClassesFromRootSchema then sv.classes.filter(_._2.definingSchema.id == sv.root.id)
      else sv.classes

    // RDFS describes URIs, while LinkML definitions are keyed by name, so several definitions may
    // describe one URI: a slot is derived once per class using it (inheritance, mixins, plain slot
    // reuse), and a class_uri, enum_uri or meaning may be repeated across definitions. Group the
    // definitions by the URI they describe, and emit each group once, at its first definition.
    // This avoids emitting repeated metadata in streaming formats.
    val classDefinitions: Map[String, Seq[ClassView]] = classes.values.toSeq.groupBy(_.uriStr)
    val propertyUsages: Map[String, Seq[(ClassView, SlotView)]] = classes.values.toSeq
      .flatMap(c => c.derivedAttributes.values.filterNot(_.inner.identifier).map((c, _)))
      .groupBy(_._2.uriStr)

    classes.values.foreach { c =>
      val classNameIri = Iri(c.uriStr)
      val definitions = classDefinitions(c.uriStr)
      if (definitions.head eq c) {
        sink.triple(classNameIri, Rdf.`type`, Rdfs.Class)
        emitCommonMetadata(sink, classNameIri, definitions)(_.cls)
        definitions
          .flatMap(d => d.cls.isA.toList ++ d.cls.mixins)
          .flatMap(m => sv.getElement(m.value).toList)
          .map(e => Iri(e.uriStr))
          .distinct
          .foreach(parent => sink.triple(classNameIri, Rdfs.subClassOf, parent))
      }
      c.derivedAttributes.values.foreach { s =>
        if (!s.inner.identifier) {
          val usages = propertyUsages(s.uriStr)
          if (usages.head._1 eq c)
            emitProperty(sink, Iri(s.uriStr), usages.map(_._2))
        }
      }
    }

    // Slots that no class uses are still properties.
    val slots =
      if onlyClassesFromRootSchema then
        sv.slotDefinitions.filter(_._2.definingSchema.id == sv.root.id)
      else sv.slotDefinitions
    slots.values.toSeq
      .filterNot(s => s.inner.identifier || propertyUsages.contains(s.uriStr))
      .groupBy(_.uriStr).toSeq.sortBy(_._1)
      .foreach((uri, definitions) => emitProperty(sink, Iri(uri), definitions.sortBy(_.name)))

    // The schema's own datatypes. LinkML's built-in types are XSD's or LinkML's own.
    val types =
      if onlyClassesFromRootSchema then sv.types.filter(_._2.definingSchema.id == sv.root.id)
      else sv.types
    types.values.toSeq
      .filterNot(t =>
        t.definingSchema.id.original.startsWith("https://w3id.org/linkml/") ||
          t.uriStr.startsWith(XmlSchema.prefix),
      )
      .groupBy(_.uriStr).toSeq.sortBy(_._1)
      .foreach { (uri, definitions) =>
        sink.triple(Iri(uri), Rdf.`type`, Rdfs.Datatype)
        emitCommonMetadata(sink, Iri(uri), definitions.sortBy(_.name))(_._type)
      }

    val enums =
      if onlyClassesFromRootSchema then sv.enums.filter(_._2.definingSchema.id == sv.root.id)
      else sv.enums

    val enumDefinitions: Map[String, Seq[EnumView]] = enums.values.toSeq.groupBy(_.uriStr)
    // A permissible value may be shared between enums, which each give it their own type but
    // possibly the same metadata, so group these by URI as well.
    val valueUsages = new mutable.HashMap[String, mutable.ArrayBuffer[(EnumView, PermissibleValue)]]
    enums.values.foreach { e =>
      e.derivedValues.foreach { v =>
        valueUsages.getOrElseUpdate(v.uriStr, new mutable.ArrayBuffer).addOne((e, v.pv))
      }
    }

    // Emit each enum as an rdfs:Class (its URI controlled by enum_uri), and each of its
    // permissible values as an instance of that class.
    enums.values.foreach { e =>
      val enumIri = Iri(e.uriStr)
      val definitions = enumDefinitions(e.uriStr)
      if (definitions.head eq e) {
        sink.triple(enumIri, Rdf.`type`, Rdfs.Class)
        emitCommonMetadata(sink, enumIri, definitions)(_._enum)
      }
      definitions.flatMap(_.parents).map(_.uriStr).distinct.foreach { parent =>
        sink.triple(enumIri, Rdfs.subClassOf, Iri(parent))
      }
      e.derivedValues.foreach { pvv =>
        val pvIri = Iri(pvv.uriStr)
        val usages = valueUsages(pvIri.value)
        val first = usages.head
        if ((first._1 eq e) && (first._2 eq pvv.pv)) {
          usages.map(u => Iri(u._1.uriStr)).distinct.foreach { enumClass =>
            sink.triple(pvIri, Rdf.`type`, enumClass)
          }
          emitCommonMetadata(sink, pvIri, usages)(_._2)
        }
      }
    }
  }
}

object RdfsGenerator {

  private val plainLiteral: Iri = Rdf.get("PlainLiteral")

  /** Options for [[RdfsGenerator]].
    *
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes and enums from the root schema (turned off by default). This
    *   is useful if you intend to generate RDFS for each schema file separately, and you don't need
    *   the imported classes to be included.
    * @param format
    *   Which RDF serialization to write: `ttl` for Turtle (the default), which is prefixed and
    *   pretty-printed, or `nt` for N-Triples.
    */
  final case class Options(
      onlyClassesFromRootSchema: Boolean = false,
      format: RdfFormat = RdfFormat.ttl,
  ) extends RdfOptions
}

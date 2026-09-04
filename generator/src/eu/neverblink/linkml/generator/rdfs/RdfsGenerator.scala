package eu.neverblink.linkml.generator.rdfs

import eu.neverblink.linkml.generator.rdf.*
import eu.neverblink.linkml.metamodel.{CommonMetadata, PermissibleValue}
import eu.neverblink.linkml.runtime.{PrefixResolver, Reference}
import eu.neverblink.linkml.schemaview.{ClassView, EnumView, SchemaView, SlotView}

class RdfsGenerator(using sv: SchemaView) extends RdfGenerator[RdfsGenerator.Options] {

  override protected def defaultOptions: RdfsGenerator.Options = RdfsGenerator.Options()

  /** Emit the RDFS metadata of every definition describing the same subject, skipping titles,
    * descriptions and links repeated between them.
    *
    * @param cms
    *   Each definition's metadata, paired with the prefix resolver of the schema that declared it.
    *   `see_also` holds CURIEs, which only mean something against the prefixes of their own schema.
    */
  private def emitCommonMetadata(
      sink: RdfSink,
      subject: Resource,
      cms: Seq[(CommonMetadata, PrefixResolver)],
  ): Unit = {
    cms.flatMap(_._1.title).distinct.foreach { t =>
      langStringProperty(sink, subject, Rdfs.label, t)
    }
    cms.flatMap(_._1.description).distinct.foreach { d =>
      langStringProperty(sink, subject, Rdfs.comment, d)
    }
    cms.flatMap((cm, pr) => cm.seeAlso.map(_.uri(using pr))).distinct.foreach { uri =>
      sink.triple(subject, Rdfs.seeAlso, Iri(uri))
    }
  }

  /** Emit a single RDFS property covering all the usages of one slot URI: the declaration and the
    * metadata once, a domain per class using it, and each distinct range it derives to.
    *
    * @param usages
    *   All (class, derived slot) pairs sharing this property URI, in class iteration order.
    */
  private def emitProperty(
      sink: RdfSink,
      propertyNameIri: Iri,
      usages: Seq[(ClassView, SlotView)],
  ): Unit = {
    sink.triple(propertyNameIri, Rdf.`type`, Rdf.Property)
    emitCommonMetadata(
      sink,
      propertyNameIri,
      usages.map(u => (u._2.slot, u._2.definingPrefixResolver)),
    )

    sv.lowestCommonAncestors(
      usages
        .flatMap(_._2.slot.domain)
        .flatMap(_.asInstanceOf[Reference[ClassView]].resolve),
    ).map(u => Iri(u.uriStr)).distinct.foreach { domain =>
      sink.triple(propertyNameIri, Rdfs.domain, domain)
    }

    usages.flatMap(_._2.derivedRange.resolve.toList).map(e => Iri(e.uriStr)).distinct.foreach {
      range => sink.triple(propertyNameIri, Rdfs.range, range)
    }

    usages.flatMap(_._2.parents).map(_.uriStr).distinct.foreach { parentUriStr =>
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
        emitCommonMetadata(
          sink,
          classNameIri,
          definitions.map(d => (d.cls, d.definingPrefixResolver)),
        )
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
          if (usages.head._1 eq c) emitProperty(sink, Iri(s.uriStr), usages)
        }
      }
    }

    val enums =
      if onlyClassesFromRootSchema then sv.enums.filter(_._2.definingSchema.id == sv.root.id)
      else sv.enums

    val enumDefinitions: Map[String, Seq[EnumView]] = enums.values.toSeq.groupBy(_.uriStr)
    // A permissible value may be shared between enums, which each give it their own type but
    // possibly the same metadata, so group these by URI as well.
    val valueUsages: Map[String, Seq[(EnumView, PermissibleValue)]] = enums.values.toSeq
      .flatMap(e =>
        e.derivedValues.map(v => (v.meaning.uri(using e.definingPrefixResolver), (e, v.pv))),
      )
      .groupMap(_._1)(_._2)

    // Emit each enum as an rdfs:Class (its URI controlled by enum_uri), and each of its
    // permissible values as an instance of that class.
    enums.values.foreach { e =>
      val prefixResolver = e.definingPrefixResolver
      val enumIri = Iri(e.uriStr)
      val definitions = enumDefinitions(e.uriStr)
      if (definitions.head eq e) {
        sink.triple(enumIri, Rdf.`type`, Rdfs.Class)
        emitCommonMetadata(sink, enumIri, definitions.map(d => (d._enum, d.definingPrefixResolver)))
      }
      definitions.flatMap(_.parents).map(_.uriStr).distinct.foreach { parent =>
        sink.triple(enumIri, Rdfs.subClassOf, Iri(parent))
      }
      e.derivedValues.foreach { (pv, meaning) =>
        val pvIri = Iri(meaning.uri(using prefixResolver))
        val usages = valueUsages(pvIri.value)
        if ((usages.head._1 eq e) && (usages.head._2 eq pv)) {
          usages.map(u => Iri(u._1.uriStr)).distinct.foreach { enumClass =>
            sink.triple(pvIri, Rdf.`type`, enumClass)
          }
          emitCommonMetadata(sink, pvIri, usages.map(u => (u._2, u._1.definingPrefixResolver)))
        }
      }
    }
  }
}

object RdfsGenerator {

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

package eu.neverblink.linkml.generator.rdfs

import eu.neverblink.linkml.generator.rdf.*
import eu.neverblink.linkml.metamodel.CommonMetadata
import eu.neverblink.linkml.schemaview.SchemaView

class RdfsGenerator(using sv: SchemaView) extends RdfGenerator {

  private def emitCommonMetadata(sink: RdfSink, subject: Resource, cm: CommonMetadata): Unit = {
    cm.title.foreach { t =>
      sink.triple(subject, Rdfs.label, Literal(t, XmlSchema.string))
    }
    cm.description.foreach { d =>
      sink.triple(subject, Rdfs.comment, Literal(d, XmlSchema.string))
    }
  }

  /** Generates RDF Schema and pushes the namespaces and triples into the provided [[RdfSink]].
    * @param sink
    *   The sink that receives namespace declarations and triples.
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes and enums from the root schema (turned off by default).
    */
  final def generate(
      sink: RdfSink,
      onlyClassesFromRootSchema: Boolean = false,
  ): Unit = {
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

    classes.values.foreach { c =>
      val classNameIri = Iri(c.uriStr)
      sink.triple(classNameIri, Rdf.`type`, Rdfs.Class)
      emitCommonMetadata(sink, classNameIri, c.cls)
      (c.cls.isA.toList ++ c.cls.mixins).foreach { m =>
        sv.getElement(m.value).foreach { e =>
          sink.triple(classNameIri, Rdfs.subClassOf, Iri(e.uriStr))
        }
      }
      c.derivedAttributes.values.foreach { s =>
        if (!s.inner.identifier) {
          val propertyNameIri = Iri(s.uriStr)
          sink.triple(propertyNameIri, Rdf.`type`, Rdf.Property)
          emitCommonMetadata(sink, propertyNameIri, s.slot)
          sink.triple(propertyNameIri, Rdfs.domain, classNameIri)
          s.derivedRange.resolve.foreach { e =>
            sink.triple(propertyNameIri, Rdfs.range, Iri(e.uriStr))
          }
        }
      }
    }

    val enums =
      if onlyClassesFromRootSchema then sv.enums.filter(_._2.definingSchema.id == sv.root.id)
      else sv.enums

    // Emit each enum as an rdfs:Class (its URI controlled by enum_uri), and each of its
    // permissible values as an instance of that class.
    enums.values.foreach { e =>
      val prefixResolver = e.definingPrefixResolver
      val enumIri = Iri(e.uriStr)
      sink.triple(enumIri, Rdf.`type`, Rdfs.Class)
      emitCommonMetadata(sink, enumIri, e._enum)
      e.derivedValues.foreach { (pv, meaning) =>
        val pvIri = Iri(meaning.uri(using prefixResolver))
        sink.triple(pvIri, Rdf.`type`, enumIri)
        emitCommonMetadata(sink, pvIri, pv)
      }
    }
  }
}

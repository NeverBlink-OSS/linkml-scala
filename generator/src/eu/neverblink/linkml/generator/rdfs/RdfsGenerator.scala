package eu.neverblink.linkml.generator.rdfs

import eu.neverblink.linkml.generator.rdf.*
import eu.neverblink.linkml.schemaview.SchemaView

class RdfsGenerator(using sv: SchemaView) {

  /** Generates RDF Schema and pushes the namespaces and triples into the provided [[RdfSink]].
    * @param sink
    *   The sink that receives namespace declarations and triples.
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes from the root schema (turned off by default).
    */
  final def generate(
      sink: RdfSink,
      onlyClassesFromRootSchema: Boolean = false,
  ): Unit = {
    val isEmitted = sv.root.defaultPrefix.foldLeft(
      sv.root.emitPrefixes.toSet,
    )((acc, p) => acc + p)
    val classes =
      if onlyClassesFromRootSchema then sv.classes.filter(_._2.definingSchema == sv.root)
      else sv.classes
    sv.root.prefixes.values.toArray
      .collect {
        case p if isEmitted(p.prefixPrefix) =>
          (p.prefixPrefix, p.prefixReference.original)
      }
      .appendedAll {
        Array(
          ("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
          ("rdfs", "http://www.w3.org/2000/01/rdf-schema#"),
          ("xsd", "http://www.w3.org/2001/XMLSchema#"),
        )
      }
      .distinct.sorted.foreach(sink.namespace)

    classes.values.foreach { c =>
      val classNameIri = Iri(c.uriStr)
      sink.triple(classNameIri, Rdf.`type`, Rdfs.Class)
      c.cls.title.foreach { t =>
        sink.triple(classNameIri, Rdfs.label, Literal(t, XmlSchema.string))
      }
      c.cls.description match {
        case Some(d) => sink.triple(classNameIri, Rdfs.comment, Literal(d, XmlSchema.string))
        case _ =>
      }
      (c.cls.isA.toList ++ c.cls.mixins).foreach { m =>
        sv.getElement(m.value).foreach { e =>
          sink.triple(classNameIri, Rdfs.subClassOf, Iri(e.uriStr))
        }
      }
      c.derivedAttributes.values.filter(!_.inner.identifier).foreach { s =>
        val propertyNameIri = Iri(s.uriStr)
        sink.triple(propertyNameIri, Rdf.`type`, Rdf.Property)
        s.slot.title.foreach { t =>
          sink.triple(propertyNameIri, Rdfs.label, Literal(t, XmlSchema.string))
        }
        s.slot.description match {
          case Some(d) => sink.triple(propertyNameIri, Rdfs.comment, Literal(d, XmlSchema.string))
          case _ =>
        }
        sink.triple(propertyNameIri, Rdfs.domain, classNameIri)
        s.derivedRangeView.resolve.foreach { e =>
          sink.triple(propertyNameIri, Rdfs.range, Iri(e.uriStr))
        }
      }
    }
  }
}

object Rdfs {
  val Class: Iri = Iri("http://www.w3.org/2000/01/rdf-schema#Class")
  // val Datatype: Iri = Iri("http://www.w3.org/2000/01/rdf-schema#Datatype")
  val comment: Iri = Iri("http://www.w3.org/2000/01/rdf-schema#comment")
  val domain: Iri = Iri("http://www.w3.org/2000/01/rdf-schema#domain")
  // val isDefinedBy: Iri = Iri("http://www.w3.org/2000/01/rdf-schema#isDefinedBy")
  val label: Iri = Iri("http://www.w3.org/2000/01/rdf-schema#label")
  val range: Iri = Iri("http://www.w3.org/2000/01/rdf-schema#range")
  // val seeAlso: Iri = Iri("http://www.w3.org/2000/01/rdf-schema#seeAlso")
  val subClassOf: Iri = Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
  // val subPropertyOf: Iri = Iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf")
}

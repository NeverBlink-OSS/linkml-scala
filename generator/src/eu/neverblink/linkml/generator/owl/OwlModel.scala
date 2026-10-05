package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.rdf.{Iri, LanguageLiteral, Literal, Node}

/** An in-memory OWL 2 ontology, close to the structural specification
  * ([[https://www.w3.org/TR/owl2-syntax/]]): a header and a flat list of axioms. Used by both the
  * OWL importer and the OWL generator.
  *
  * It differs from the specification in two ways:
  *   - Expressions don't split properties into object and data properties. A restriction is a data
  *     restriction when its filler is a [[DataRange]]. Each property's kind comes from its
  *     [[Axiom.Declaration]].
  *   - Anonymous individuals can only be annotation values.
  */
final case class Ontology(
    iri: Option[String] = None,
    versionIri: Option[String] = None,
    imports: Seq[String] = Nil,
    annotations: Seq[Annotation] = Nil,
    prefixes: Seq[(String, String)] = Nil,
    axioms: Seq[Axiom] = Nil,
)

/** `sh:prefix`. `Shacl.prefix` can't be used, because it is the SHACL namespace. */
private[owl] val shaclPrefix: Iri = eu.neverblink.linkml.rdf.Shacl.get("prefix")

final case class Annotation(property: String, value: Node)

object Annotation {
  def text(property: String, value: String, language: Option[String] = None): Annotation =
    Annotation(property, language.fold(Literal(value))(LanguageLiteral(value, _)))
}

enum EntityKind:
  case Class, Datatype, ObjectProperty, DataProperty, AnnotationProperty, NamedIndividual

/** What a restriction ranges over. */
type Filler = ClassExpr | DataRange

final case class PropertyRef(iri: String, inverse: Boolean = false)

/** An individual or a literal, as in `owl:hasValue` or a property assertion. */
type Value = Iri | Literal | LanguageLiteral

sealed trait ClassExpr extends Product

object ClassExpr {
  final case class Named(iri: String) extends ClassExpr
  final case class IntersectionOf(operands: Seq[ClassExpr]) extends ClassExpr
  final case class UnionOf(operands: Seq[ClassExpr]) extends ClassExpr
  final case class ComplementOf(operand: ClassExpr) extends ClassExpr
  final case class OneOf(individuals: Seq[String]) extends ClassExpr
  final case class SomeValuesFrom(property: PropertyRef, filler: Filler) extends ClassExpr
  final case class AllValuesFrom(property: PropertyRef, filler: Filler) extends ClassExpr
  final case class HasValue(property: PropertyRef, value: Value) extends ClassExpr
  final case class HasSelf(property: PropertyRef) extends ClassExpr

  /** `owl:minCardinality` and the like. With a filler, it's the qualified form. */
  final case class Cardinality(
      bound: Bound,
      n: Int,
      property: PropertyRef,
      filler: Option[Filler] = None,
  ) extends ClassExpr

  enum Bound:
    case Min, Max, Exact

  val Thing: Named = Named("http://www.w3.org/2002/07/owl#Thing")
  val Nothing: Named = Named("http://www.w3.org/2002/07/owl#Nothing")
}

sealed trait DataRange extends Product

object DataRange {
  final case class Datatype(iri: String) extends DataRange
  final case class IntersectionOf(operands: Seq[DataRange]) extends DataRange
  final case class UnionOf(operands: Seq[DataRange]) extends DataRange
  final case class ComplementOf(operand: DataRange) extends DataRange
  final case class OneOf(values: Seq[Literal | LanguageLiteral]) extends DataRange

  /** A datatype narrowed by facets, e.g. `xsd:integer[>= 1]`. */
  final case class Restriction(datatype: String, facets: Seq[(String, Literal)]) extends DataRange

  val Literal: Datatype = Datatype("http://www.w3.org/2000/01/rdf-schema#Literal")
}

/** An OWL axiom. See [[OwlRdfWriter]] for the RDF triples each one maps to. */
sealed trait Axiom extends Product {

  /** Annotations on the axiom itself. In RDF they go on an `owl:Axiom` node. */
  def annotations: Seq[Annotation]
}

object Axiom {
  final case class Declaration(kind: EntityKind, iri: String, annotations: Seq[Annotation] = Nil)
      extends Axiom

  final case class SubClassOf(
      sub: ClassExpr,
      sup: ClassExpr,
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  final case class EquivalentClasses(
      operands: Seq[ClassExpr],
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  final case class DisjointClasses(operands: Seq[ClassExpr], annotations: Seq[Annotation] = Nil)
      extends Axiom

  final case class DisjointUnion(
      cls: String,
      operands: Seq[ClassExpr],
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  final case class SubPropertyOf(sub: String, sup: String, annotations: Seq[Annotation] = Nil)
      extends Axiom

  /** `owl:propertyChainAxiom`: the chain implies `sup`. */
  final case class PropertyChain(
      sup: String,
      chain: Seq[PropertyRef],
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  final case class EquivalentProperties(
      operands: Seq[String],
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  final case class DisjointProperties(operands: Seq[String], annotations: Seq[Annotation] = Nil)
      extends Axiom

  final case class InverseProperties(
      first: String,
      second: String,
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  final case class Domain(property: String, domain: ClassExpr, annotations: Seq[Annotation] = Nil)
      extends Axiom

  final case class Range(property: String, range: Filler, annotations: Seq[Annotation] = Nil)
      extends Axiom

  /** A property characteristic, written as an `rdf:type` of the property. */
  final case class Characteristic(
      property: String,
      characteristic: PropertyCharacteristic,
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  final case class DatatypeDefinition(
      datatype: String,
      range: DataRange,
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  final case class HasKey(
      cls: ClassExpr,
      properties: Seq[String],
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  final case class ClassAssertion(
      cls: ClassExpr,
      individual: String,
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  /** An object or data property assertion: `individual property value`. */
  final case class PropertyAssertion(
      property: String,
      individual: String,
      value: Value,
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  final case class SameIndividual(operands: Seq[String], annotations: Seq[Annotation] = Nil)
      extends Axiom

  final case class DifferentIndividuals(
      operands: Seq[String],
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom

  /** The subject doesn't have to be declared in this ontology. */
  final case class AnnotationAssertion(
      subject: String,
      annotation: Annotation,
      annotations: Seq[Annotation] = Nil,
  ) extends Axiom
}

enum PropertyCharacteristic(val iri: Iri):
  case Functional extends PropertyCharacteristic(eu.neverblink.linkml.rdf.Owl.FunctionalProperty)
  case InverseFunctional
      extends PropertyCharacteristic(eu.neverblink.linkml.rdf.Owl.InverseFunctionalProperty)
  case Transitive extends PropertyCharacteristic(eu.neverblink.linkml.rdf.Owl.TransitiveProperty)
  case Symmetric extends PropertyCharacteristic(eu.neverblink.linkml.rdf.Owl.SymmetricProperty)
  case Asymmetric extends PropertyCharacteristic(eu.neverblink.linkml.rdf.Owl.AsymmetricProperty)
  case Reflexive extends PropertyCharacteristic(eu.neverblink.linkml.rdf.Owl.ReflexiveProperty)
  case Irreflexive extends PropertyCharacteristic(eu.neverblink.linkml.rdf.Owl.IrreflexiveProperty)

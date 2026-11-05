package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.generator.owl.ClassExpr.Bound
import eu.neverblink.linkml.rdf.{Iri, LanguageLiteral, Literal, Node}

/** Renders OWL axioms and expressions as short text, roughly in Manchester syntax. Used in the
  * importer report and in notes.
  *
  * @param short
  *   Shortens an IRI to a CURIE where possible.
  */
private[owl] final class OwlRender(short: String => String) {

  def apply(f: Filler): String = f match {
    case e: ClassExpr.Named => short(e.iri)
    case e: ClassExpr.IntersectionOf => e.operands.iterator.map(apply).mkString("(", " and ", ")")
    case e: ClassExpr.UnionOf => e.operands.iterator.map(apply).mkString("(", " or ", ")")
    case e: ClassExpr.ComplementOf => "not ".concat(apply(e.operand))
    case e: ClassExpr.OneOf => e.individuals.iterator.map(short).mkString("{", ", ", "}")
    case e: ClassExpr.SomeValuesFrom => apply(e.property) + " some " + apply(e.filler)
    case e: ClassExpr.AllValuesFrom => apply(e.property) + " only " + apply(e.filler)
    case e: ClassExpr.HasValue => apply(e.property) + " value " + value(e.value)
    case e: ClassExpr.HasSelf => apply(e.property).concat(" Self")
    case e: ClassExpr.Cardinality =>
      val word = e.bound match {
        case Bound.Min => " min "
        case Bound.Max => " max "
        case Bound.Exact => " exactly "
      }
      val text = apply(e.property) + word + e.n
      if (e.filler.isDefined) text + " " + apply(e.filler.get) else text
    case r: DataRange.Datatype => short(r.iri)
    case r: DataRange.IntersectionOf => r.operands.iterator.map(apply).mkString("(", " and ", ")")
    case r: DataRange.UnionOf => r.operands.iterator.map(apply).mkString("(", " or ", ")")
    case r: DataRange.ComplementOf => "not ".concat(apply(r.operand))
    case r: DataRange.OneOf => r.values.iterator.map(value).mkString("{", ", ", "}")
    case r: DataRange.Restriction =>
      r.facets.iterator.map(f => short(f._1) + " " + f._2.value)
        .mkString(short(r.datatype).concat("["), ", ", "]")
  }

  def apply(p: PropertyRef): String =
    if (p.inverse) "inverse ".concat(short(p.iri)) else short(p.iri)

  def apply(a: Axiom): String = a match {
    case x: SubClassOf => apply(x.sub) + " subClassOf " + apply(x.sup)
    case x: EquivalentClasses => x.operands.iterator.map(apply).mkString(" equivalentTo ")
    case x: DisjointClasses => x.operands.iterator.map(apply).mkString("disjoint(", ", ", ")")
    case x: DisjointUnion =>
      short(x.cls) + " disjointUnionOf " + x.operands.iterator.map(apply).mkString(", ")
    case x: Domain => short(x.property) + " domain " + apply(x.domain)
    case x: Range => short(x.property) + " range " + apply(x.range)
    case x: SubPropertyOf => short(x.sub) + " subPropertyOf " + short(x.sup)
    case x: HasKey => apply(x.cls) + " hasKey " + x.properties.iterator.map(short).mkString(", ")
    case x: DatatypeDefinition => short(x.datatype) + " equivalentTo " + apply(x.range)
    case x: Characteristic => short(x.property) + " " + x.characteristic
    case x: PropertyChain =>
      x.chain.iterator.map(apply).mkString(" o ") + " subPropertyOf " + short(x.sup)
    case x: EquivalentProperties => x.operands.iterator.map(short).mkString(" equivalentTo ")
    case x: DisjointProperties => x.operands.iterator.map(short).mkString("disjoint(", ", ", ")")
    case x: InverseProperties => short(x.first) + " inverseOf " + short(x.second)
    case x: ClassAssertion => short(x.individual) + " type " + apply(x.cls)
    case x: PropertyAssertion =>
      short(x.individual) + " " + short(x.property) + " " + value(x.value)
    case x: SameIndividual => x.operands.iterator.map(short).mkString(" sameAs ")
    case x: DifferentIndividuals =>
      x.operands.iterator.map(short).mkString("different(", ", ", ")")
    case x: Declaration => x.kind.toString + " " + short(x.iri)
    case x: AnnotationAssertion =>
      short(x.subject) + " " + short(x.annotation.property) + " " + value(x.annotation.value)
  }

  private def value(v: Node): String = v match {
    case i: Iri => short(i.value)
    case l: Literal => "\"" + l.value + "\""
    case l: LanguageLiteral => "\"" + l.value + "\"@" + l.language
    case other => other.toString
  }
}

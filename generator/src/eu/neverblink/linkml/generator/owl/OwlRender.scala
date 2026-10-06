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
    case ClassExpr.Named(iri) => short(iri)
    case ClassExpr.IntersectionOf(ops) => ops.map(apply).mkString("(", " and ", ")")
    case ClassExpr.UnionOf(ops) => ops.map(apply).mkString("(", " or ", ")")
    case ClassExpr.ComplementOf(op) => s"not ${apply(op)}"
    case ClassExpr.OneOf(is) => is.map(short).mkString("{", ", ", "}")
    case ClassExpr.SomeValuesFrom(p, f) => s"${apply(p)} some ${apply(f)}"
    case ClassExpr.AllValuesFrom(p, f) => s"${apply(p)} only ${apply(f)}"
    case ClassExpr.HasValue(p, v) => s"${apply(p)} value ${value(v)}"
    case ClassExpr.HasSelf(p) => s"${apply(p)} Self"
    case ClassExpr.Cardinality(b, n, p, f) =>
      val word = b match {
        case Bound.Min => "min"
        case Bound.Max => "max"
        case Bound.Exact => "exactly"
      }
      s"${apply(p)} $word $n" + f.fold("")(x => " " + apply(x))
    case DataRange.Datatype(iri) => short(iri)
    case DataRange.IntersectionOf(ops) => ops.map(apply).mkString("(", " and ", ")")
    case DataRange.UnionOf(ops) => ops.map(apply).mkString("(", " or ", ")")
    case DataRange.ComplementOf(op) => s"not ${apply(op)}"
    case DataRange.OneOf(vs) => vs.map(value).mkString("{", ", ", "}")
    case DataRange.Restriction(d, facets) =>
      facets.map((f, v) => s"${short(f)} ${v.value}").mkString(s"${short(d)}[", ", ", "]")
  }

  def apply(p: PropertyRef): String = if p.inverse then s"inverse ${short(p.iri)}" else short(p.iri)

  def apply(a: Axiom): String = a match {
    case SubClassOf(sub, sup, _) => s"${apply(sub)} subClassOf ${apply(sup)}"
    case EquivalentClasses(ops, _) => ops.map(apply).mkString(" equivalentTo ")
    case DisjointClasses(ops, _) => ops.map(apply).mkString("disjoint(", ", ", ")")
    case DisjointUnion(c, ops, _) => s"${short(c)} disjointUnionOf ${ops.map(apply).mkString(", ")}"
    case Domain(p, d, _) => s"${short(p)} domain ${apply(d)}"
    case Range(p, r, _) => s"${short(p)} range ${apply(r)}"
    case SubPropertyOf(a, b, _) => s"${short(a)} subPropertyOf ${short(b)}"
    case HasKey(c, ps, _) => s"${apply(c)} hasKey ${ps.map(short).mkString(", ")}"
    case DatatypeDefinition(d, r, _) => s"${short(d)} equivalentTo ${apply(r)}"
    case Characteristic(p, c, _) => s"${short(p)} $c"
    case PropertyChain(sup, chain, _) =>
      s"${chain.map(apply).mkString(" o ")} subPropertyOf ${short(sup)}"
    case EquivalentProperties(ops, _) => ops.map(short).mkString(" equivalentTo ")
    case DisjointProperties(ops, _) => ops.map(short).mkString("disjoint(", ", ", ")")
    case InverseProperties(a, b, _) => s"${short(a)} inverseOf ${short(b)}"
    case ClassAssertion(c, i, _) => s"${short(i)} type ${apply(c)}"
    case PropertyAssertion(p, i, v, _) => s"${short(i)} ${short(p)} ${value(v)}"
    case SameIndividual(ops, _) => ops.map(short).mkString(" sameAs ")
    case DifferentIndividuals(ops, _) => ops.map(short).mkString("different(", ", ", ")")
    case Declaration(kind, iri, _) => s"$kind ${short(iri)}"
    case AnnotationAssertion(s, a, _) => s"${short(s)} ${short(a.property)} ${value(a.value)}"
  }

  private def value(v: Node): String = v match {
    case Iri(i) => short(i)
    case Literal(l, _) => s"\"$l\""
    case LanguageLiteral(l, lang) => s"\"$l\"@$lang"
    case other => other.toString
  }
}

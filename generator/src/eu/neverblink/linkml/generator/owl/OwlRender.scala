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

  def apply(f: Filler): String = {
    val sb = new java.lang.StringBuilder
    filler(sb, f)
    sb.toString
  }

  def apply(p: PropertyRef): String = {
    val sb = new java.lang.StringBuilder
    property(sb, p)
    sb.toString
  }

  def apply(a: Axiom): String = {
    val sb = new java.lang.StringBuilder
    axiom(sb, a)
    sb.toString
  }

  private def filler(sb: java.lang.StringBuilder, f: Filler): Unit = f match {
    case e: ClassExpr.Named => sb.append(short(e.iri))
    case e: ClassExpr.IntersectionOf => fillers(sb, e.operands, "(", " and ", ")")
    case e: ClassExpr.UnionOf => fillers(sb, e.operands, "(", " or ", ")")
    case e: ClassExpr.ComplementOf =>
      sb.append("not ")
      filler(sb, e.operand)
    case e: ClassExpr.OneOf => iris(sb, e.individuals, "{", ", ", "}")
    case e: ClassExpr.SomeValuesFrom =>
      property(sb, e.property)
      sb.append(" some ")
      filler(sb, e.filler)
    case e: ClassExpr.AllValuesFrom =>
      property(sb, e.property)
      sb.append(" only ")
      filler(sb, e.filler)
    case e: ClassExpr.HasValue =>
      property(sb, e.property)
      sb.append(" value ")
      value(sb, e.value)
    case e: ClassExpr.HasSelf =>
      property(sb, e.property)
      sb.append(" Self")
    case e: ClassExpr.Cardinality =>
      property(sb, e.property)
      sb.append(e.bound match {
        case Bound.Min => " min "
        case Bound.Max => " max "
        case Bound.Exact => " exactly "
      }).append(e.n)
      if (e.filler.isDefined) {
        sb.append(' ')
        filler(sb, e.filler.get)
      }
    case r: DataRange.Datatype => sb.append(short(r.iri))
    case r: DataRange.IntersectionOf => fillers(sb, r.operands, "(", " and ", ")")
    case r: DataRange.UnionOf => fillers(sb, r.operands, "(", " or ", ")")
    case r: DataRange.ComplementOf =>
      sb.append("not ")
      filler(sb, r.operand)
    case r: DataRange.OneOf =>
      sb.append('{')
      var first = true
      r.values.foreach { v =>
        if (!first) sb.append(", ")
        first = false
        value(sb, v)
      }
      sb.append('}')
    case r: DataRange.Restriction =>
      sb.append(short(r.datatype)).append('[')
      var first = true
      r.facets.foreach { f =>
        if (!first) sb.append(", ")
        first = false
        sb.append(short(f._1)).append(' ').append(f._2.value)
      }
      sb.append(']')
  }

  private def fillers(
      sb: java.lang.StringBuilder,
      fs: Seq[Filler],
      start: String,
      separator: String,
      end: String,
  ): Unit = {
    sb.append(start)
    var first = true
    fs.foreach { f =>
      if (!first) sb.append(separator)
      first = false
      filler(sb, f)
    }
    sb.append(end)
  }

  private def iris(
      sb: java.lang.StringBuilder,
      is: Seq[String],
      start: String,
      separator: String,
      end: String,
  ): Unit = {
    sb.append(start)
    var first = true
    is.foreach { i =>
      if (!first) sb.append(separator)
      first = false
      sb.append(short(i))
    }
    sb.append(end)
  }

  private def property(sb: java.lang.StringBuilder, p: PropertyRef): Unit = {
    if (p.inverse) sb.append("inverse ")
    sb.append(short(p.iri))
  }

  private def axiom(sb: java.lang.StringBuilder, a: Axiom): Unit = a match {
    case x: SubClassOf =>
      filler(sb, x.sub)
      sb.append(" subClassOf ")
      filler(sb, x.sup)
    case x: EquivalentClasses => fillers(sb, x.operands, "", " equivalentTo ", "")
    case x: DisjointClasses => fillers(sb, x.operands, "disjoint(", ", ", ")")
    case x: DisjointUnion =>
      sb.append(short(x.cls))
      fillers(sb, x.operands, " disjointUnionOf ", ", ", "")
    case x: Domain =>
      sb.append(short(x.property)).append(" domain ")
      filler(sb, x.domain)
    case x: Range =>
      sb.append(short(x.property)).append(" range ")
      filler(sb, x.range)
    case x: SubPropertyOf =>
      sb.append(short(x.sub)).append(" subPropertyOf ").append(short(x.sup))
    case x: HasKey =>
      filler(sb, x.cls)
      iris(sb, x.properties, " hasKey ", ", ", "")
    case x: DatatypeDefinition =>
      sb.append(short(x.datatype)).append(" equivalentTo ")
      filler(sb, x.range)
    case x: Characteristic => sb.append(short(x.property)).append(' ').append(x.characteristic)
    case x: PropertyChain =>
      var first = true
      x.chain.foreach { p =>
        if (!first) sb.append(" o ")
        first = false
        property(sb, p)
      }
      sb.append(" subPropertyOf ").append(short(x.sup))
    case x: EquivalentProperties => iris(sb, x.operands, "", " equivalentTo ", "")
    case x: DisjointProperties => iris(sb, x.operands, "disjoint(", ", ", ")")
    case x: InverseProperties =>
      sb.append(short(x.first)).append(" inverseOf ").append(short(x.second))
    case x: ClassAssertion =>
      sb.append(short(x.individual)).append(" type ")
      filler(sb, x.cls)
    case x: PropertyAssertion =>
      sb.append(short(x.individual)).append(' ').append(short(x.property)).append(' ')
      value(sb, x.value)
    case x: SameIndividual => iris(sb, x.operands, "", " sameAs ", "")
    case x: DifferentIndividuals => iris(sb, x.operands, "different(", ", ", ")")
    case x: Declaration => sb.append(x.kind).append(' ').append(short(x.iri))
    case x: AnnotationAssertion =>
      sb.append(short(x.subject)).append(' ').append(short(x.annotation.property)).append(' ')
      value(sb, x.annotation.value)
  }

  private def value(sb: java.lang.StringBuilder, v: Node): Unit = v match {
    case i: Iri => sb.append(short(i.value))
    case l: Literal => sb.append('"').append(l.value).append('"')
    case l: LanguageLiteral => sb.append('"').append(l.value).append("\"@").append(l.language)
    case other => sb.append(other)
  }
}

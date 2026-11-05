package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.rdf.Iri

import scala.collection.mutable

/** Indexes an ontology's axioms for the importer and tracks which ones it has turned into LinkML.
  * The importer reports the ones left over.
  *
  * The entries of each index are in the order of the axioms.
  */
private[owl] final class OwlAxiomIndex(val axioms: Seq[Axiom]) {
  import OwlAxiomIndex.*

  private val handled = mutable.HashSet.empty[Axiom]

  /** Marks an axiom as turned into LinkML and returns it. */
  def take[A <: Axiom](a: A): A = {
    handled.addOne(a)
    a
  }

  /** Calls `f` with each axiom that was not turned into LinkML, in order. */
  def foreachUnhandled(f: Axiom => Unit): Unit = axioms.foreach(a => if (!handled.contains(a)) f(a))

  /** The kinds each IRI is declared as, one bit per [[EntityKind]]. */
  val declared = mutable.HashMap.empty[String, Int]

  def isDeclared(iri: String, kind: EntityKind): Boolean =
    (declared.getOrElse(iri, 0) & (1 << kind.ordinal)) != 0

  /** Annotations by subject. All of them are marked as handled here, and the importer reports the
    * ones it can't use itself.
    */
  val annotationsOf = mutable.HashMap.empty[String, mutable.ArrayBuffer[Annotation]]

  /** The superclasses of each named class. */
  val subClassOf =
    mutable.HashMap.empty[String, mutable.ArrayBuffer[(axiom: SubClassOf, sup: ClassExpr)]]

  /** Maps the first named class in each equivalence to the other operands. */
  val equivalents = mutable.HashMap
    .empty[String, mutable.ArrayBuffer[(axiom: EquivalentClasses, other: ClassExpr)]]

  val domains =
    mutable.HashMap.empty[String, mutable.ArrayBuffer[(axiom: Domain, domain: ClassExpr)]]

  val ranges = mutable.HashMap.empty[String, mutable.ArrayBuffer[(axiom: Range, range: Filler)]]

  val characteristicsOf = mutable.HashMap.empty[String, mutable.ArrayBuffer[Characteristic]]

  def isFunctional(property: String): Boolean = {
    val characteristics = characteristicsOf.getOrElse(property, null)
    (characteristics ne null) &&
    characteristics.exists(_.characteristic == PropertyCharacteristic.Functional)
  }

  val classAssertions = mutable.ArrayBuffer.empty[ClassAssertion]

  val assertionsByIndividual = mutable.HashMap.empty[String, mutable.ArrayBuffer[ClassAssertion]]

  val datatypeDefinitions = mutable.HashMap.empty[String, DatatypeDefinition]

  /** Classes that have a named subclass. */
  val namedSubclasses = mutable.HashSet.empty[String]

  /** Classes that are an operand of an equivalence. */
  val inEquivalence = mutable.HashSet.empty[String]

  /** Classes that are an operand of an equivalence with an operand that is not a named class. */
  val inExpressionEquivalence = mutable.HashSet.empty[String]

  /** Classes that appear anywhere in the domain of some property, nested ones included. */
  val domainClasses = mutable.HashSet.empty[String]

  val superProperties =
    mutable.HashMap.empty[String, mutable.ArrayBuffer[(axiom: SubPropertyOf, sup: String)]]

  val chains = mutable.HashMap.empty[String, mutable.ArrayBuffer[PropertyChain]]

  /** Maps the first property in each equivalence to the others. */
  val equivalentProperties = mutable.HashMap
    .empty[String, mutable.ArrayBuffer[(axiom: EquivalentProperties, other: String)]]

  val inverses = mutable.ArrayBuffer.empty[InverseProperties]

  /** The disjointness axioms of each named class in them. */
  val disjointByClass = mutable.HashMap.empty[String, mutable.ArrayBuffer[DisjointClasses]]

  val disjointUnions = mutable.HashMap.empty[String, DisjointUnion]

  val keysByClass = mutable.HashMap.empty[String, mutable.ArrayBuffer[HasKey]]

  val declarationsByIri = mutable.HashMap.empty[String, mutable.ArrayBuffer[Declaration]]

  /** Class expressions used directly in an axiom. Nested ones are not included. */
  val expressions = mutable.ArrayBuffer.empty[ClassExpr]

  axioms.foreach {
    case a: Declaration =>
      declared.update(a.iri, declared.getOrElse(a.iri, 0) | (1 << a.kind.ordinal))
      add(declarationsByIri, a.iri, a)
    case a: AnnotationAssertion => add(annotationsOf, a.subject, take(a).annotation)
    case a: SubClassOf =>
      a.sub match {
        case n: ClassExpr.Named =>
          add(subClassOf, n.iri, (axiom = a, sup = a.sup))
          a.sup match {
            case sup: ClassExpr.Named => namedSubclasses.addOne(sup.iri)
            case _ =>
          }
        case _ =>
      }
      expressions.addOne(a.sub)
      expressions.addOne(a.sup)
    case a: EquivalentClasses =>
      var first: ClassExpr.Named = null
      var allNamed = true
      a.operands.foreach {
        case n: ClassExpr.Named =>
          if (first eq null) first = n
          inEquivalence.addOne(n.iri)
        case _ => allNamed = false
      }
      if (!allNamed) {
        a.operands.foreach {
          case n: ClassExpr.Named => inExpressionEquivalence.addOne(n.iri)
          case _ =>
        }
      }
      if (first ne null) {
        a.operands.foreach { other =>
          if (other != first) add(equivalents, first.iri, (axiom = a, other = other))
        }
      }
      expressions.addAll(a.operands)
    case a: DisjointClasses =>
      a.operands.foreach {
        case n: ClassExpr.Named => add(disjointByClass, n.iri, a)
        case _ =>
      }
      expressions.addAll(a.operands)
    case a: DisjointUnion =>
      disjointUnions.update(a.cls, a)
      expressions.addOne(ClassExpr.Named(a.cls))
      expressions.addAll(a.operands)
    case a: Domain =>
      add(domains, a.property, (axiom = a, domain = a.domain))
      foreachClassIn(a.domain)(domainClasses.addOne)
      expressions.addOne(a.domain)
    case a: Range =>
      add(ranges, a.property, (axiom = a, range = a.range))
      a.range match {
        case r: ClassExpr => expressions.addOne(r)
        case _ =>
      }
    case a: HasKey =>
      a.cls match {
        case n: ClassExpr.Named => add(keysByClass, n.iri, a)
        case _ =>
      }
      expressions.addOne(a.cls)
    case a: ClassAssertion =>
      classAssertions.addOne(a)
      add(assertionsByIndividual, a.individual, a)
      expressions.addOne(a.cls)
    case a: Characteristic => add(characteristicsOf, a.property, a)
    case a: DatatypeDefinition => datatypeDefinitions.update(a.datatype, a)
    case a: SubPropertyOf => add(superProperties, a.sub, (axiom = a, sup = a.sup))
    case a: PropertyChain => add(chains, a.sup, a)
    case a: EquivalentProperties =>
      if (a.operands.nonEmpty) {
        val first = a.operands.head
        a.operands.iterator.drop(1)
          .foreach(o => add(equivalentProperties, first, (axiom = a, other = o)))
      }
    case a: InverseProperties => inverses.addOne(a)
    case _ =>
  }
}

private[owl] object OwlAxiomIndex {

  private def add[A](
      index: mutable.HashMap[String, mutable.ArrayBuffer[A]],
      key: String,
      value: A,
  ): Unit = index.getOrElseUpdate(key, mutable.ArrayBuffer.empty).addOne(value)

  /** Calls `f` with each named class in an expression, nested ones included, in order. */
  def foreachClassIn(e: Filler)(f: String => Unit): Unit = e match {
    case x: ClassExpr.Named => f(x.iri)
    case x: ClassExpr.IntersectionOf => x.operands.foreach(foreachClassIn(_)(f))
    case x: ClassExpr.UnionOf => x.operands.foreach(foreachClassIn(_)(f))
    case x: ClassExpr.ComplementOf => foreachClassIn(x.operand)(f)
    case x: ClassExpr.SomeValuesFrom => foreachClassIn(x.filler)(f)
    case x: ClassExpr.AllValuesFrom => foreachClassIn(x.filler)(f)
    case x: ClassExpr.Cardinality => if (x.filler.isDefined) foreachClassIn(x.filler.get)(f)
    case _ =>
  }

  /** Calls `f` with each property restricted in an expression, in order, and whether it is a data
    * restriction.
    */
  def foreachPropertyIn(e: Filler)(f: (String, Boolean) => Unit): Unit = e match {
    case x: ClassExpr.IntersectionOf => x.operands.foreach(foreachPropertyIn(_)(f))
    case x: ClassExpr.UnionOf => x.operands.foreach(foreachPropertyIn(_)(f))
    case x: ClassExpr.ComplementOf => foreachPropertyIn(x.operand)(f)
    case x: ClassExpr.SomeValuesFrom =>
      f(x.property.iri, x.filler.isInstanceOf[DataRange])
      foreachPropertyIn(x.filler)(f)
    case x: ClassExpr.AllValuesFrom =>
      f(x.property.iri, x.filler.isInstanceOf[DataRange])
      foreachPropertyIn(x.filler)(f)
    case x: ClassExpr.HasValue => f(x.property.iri, !x.value.isInstanceOf[Iri])
    case x: ClassExpr.HasSelf => f(x.property.iri, false)
    case x: ClassExpr.Cardinality =>
      val filler = x.filler
      f(x.property.iri, filler.isDefined && filler.get.isInstanceOf[DataRange])
      if (filler.isDefined) foreachPropertyIn(filler.get)(f)
    case _ =>
  }

  /** Calls `f` with each datatype in a range or expression, nested ones included, in order. */
  def foreachDatatypeIn(r: Filler)(f: String => Unit): Unit = r match {
    case x: DataRange.Datatype => f(x.iri)
    case x: DataRange.Restriction => f(x.datatype)
    case x: DataRange.UnionOf => x.operands.foreach(foreachDatatypeIn(_)(f))
    case x: DataRange.IntersectionOf => x.operands.foreach(foreachDatatypeIn(_)(f))
    case x: DataRange.ComplementOf => foreachDatatypeIn(x.operand)(f)
    case x: ClassExpr.SomeValuesFrom => foreachDatatypeIn(x.filler)(f)
    case x: ClassExpr.AllValuesFrom => foreachDatatypeIn(x.filler)(f)
    case x: ClassExpr.Cardinality => if (x.filler.isDefined) foreachDatatypeIn(x.filler.get)(f)
    case x: ClassExpr.IntersectionOf => x.operands.foreach(foreachDatatypeIn(_)(f))
    case x: ClassExpr.UnionOf => x.operands.foreach(foreachDatatypeIn(_)(f))
    case _ =>
  }
}

package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.rdf.Iri

import scala.collection.mutable

/** Indexes an ontology's axioms for the importer and tracks which ones it has turned into LinkML.
  * The importer reports the ones left over.
  */
private[owl] final class OwlAxiomIndex(val axioms: Seq[Axiom]) {
  import OwlAxiomIndex.*

  private val handled = mutable.HashSet.empty[Axiom]

  /** Marks an axiom as turned into LinkML and returns it. */
  def take[A <: Axiom](a: A): A = {
    handled += a
    a
  }

  def unhandled: Seq[Axiom] = axioms.filterNot(handled)

  val declared: Map[String, Set[EntityKind]] =
    axioms.collect { case Declaration(k, iri, _) => iri -> k }.groupMap(_._1)(_._2)
      .view.mapValues(_.toSet).toMap

  def isDeclared(iri: String, kind: EntityKind): Boolean =
    declared.get(iri).exists(_.contains(kind))

  /** Annotations by subject. All of them are marked as handled here, and the importer reports the
    * ones it can't use itself.
    */
  val annotationsOf: Map[String, Seq[Annotation]] =
    axioms.collect { case a: AnnotationAssertion => take(a) }
      .groupMap(_.subject)(_.annotation)

  val subClassOf: Map[String, Seq[(SubClassOf, ClassExpr)]] =
    axioms.collect { case a @ SubClassOf(ClassExpr.Named(s), sup, _) => s -> (a, sup) }
      .groupMap(_._1)(_._2)

  /** Maps the first named class in each equivalence to the other operands. */
  val equivalents: Map[String, Seq[(EquivalentClasses, ClassExpr)]] =
    axioms.collect { case a: EquivalentClasses => a }.flatMap { a =>
      a.operands.collectFirst { case ClassExpr.Named(c) => c }.toSeq.flatMap { c =>
        a.operands.filterNot(_ == ClassExpr.Named(c)).map(other => c -> (a, other))
      }
    }.groupMap(_._1)(_._2)

  val domains: Map[String, Seq[(Domain, ClassExpr)]] =
    axioms.collect { case a @ Domain(p, d, _) => p -> (a, d) }.groupMap(_._1)(_._2)

  val ranges: Map[String, Seq[(Range, Filler)]] =
    axioms.collect { case a @ Range(p, r, _) => p -> (a, r) }.groupMap(_._1)(_._2)

  val characteristicsOf: Map[String, Seq[Characteristic]] =
    axioms.collect { case a: Characteristic => a }.groupBy(_.property)

  def isFunctional(property: String): Boolean =
    characteristicsOf.getOrElse(property, Nil)
      .exists(_.characteristic == PropertyCharacteristic.Functional)

  val classAssertions: Seq[ClassAssertion] = axioms.collect { case a: ClassAssertion => a }

  val assertionsByIndividual: Map[String, Seq[ClassAssertion]] =
    classAssertions.groupBy(_.individual)

  val datatypeDefinitions: Map[String, DatatypeDefinition] =
    axioms.collect { case a: DatatypeDefinition => a.datatype -> a }.toMap

  val namedSubclasses: Map[String, Seq[String]] =
    subClassOf.toSeq.flatMap((sub, sups) =>
      sups.collect { case (_, ClassExpr.Named(sup)) => sup -> sub },
    ).groupMap(_._1)(_._2)

  val inEquivalence: Set[String] =
    axioms.collect { case EquivalentClasses(ops, _) =>
      ops.collect { case ClassExpr.Named(c) => c }
    }
      .flatten.toSet

  /** Classes that appear anywhere in the domain of some property, nested ones included. */
  val domainClasses: Set[String] = domains.values.flatten.flatMap((_, d) => classesIn(d)).toSet

  val superProperties: Map[String, Seq[(SubPropertyOf, String)]] =
    axioms.collect { case a @ SubPropertyOf(sub, sup, _) => sub -> (a, sup) }.groupMap(_._1)(_._2)

  val chains: Map[String, Seq[PropertyChain]] =
    axioms.collect { case a: PropertyChain => a }.groupBy(_.sup)

  /** Maps the first property in each equivalence to the others. */
  val equivalentProperties: Map[String, Seq[(EquivalentProperties, String)]] =
    axioms.collect { case a: EquivalentProperties => a }.flatMap { a =>
      a.operands.headOption.toSeq.flatMap(h => a.operands.tail.map(o => h -> (a, o)))
    }.groupMap(_._1)(_._2)

  val inverses: Seq[InverseProperties] = axioms.collect { case a: InverseProperties => a }

  val disjointByClass: Map[String, Seq[DisjointClasses]] =
    axioms.collect { case a: DisjointClasses => a }
      .flatMap(a => a.operands.collect { case ClassExpr.Named(c) => c -> a })
      .groupMap(_._1)(_._2)

  val disjointUnions: Map[String, DisjointUnion] =
    axioms.collect { case a: DisjointUnion => a.cls -> a }.toMap

  val keysByClass: Map[String, Seq[HasKey]] =
    axioms.collect { case a @ HasKey(ClassExpr.Named(c), _, _) => c -> a }.groupMap(_._1)(_._2)

  val declarationsByIri: Map[String, Seq[Declaration]] =
    axioms.collect { case a: Declaration => a }.groupBy(_.iri)

  /** Class expressions used directly in an axiom. Nested ones are not included. */
  val expressions: Seq[ClassExpr] = axioms.flatMap {
    case SubClassOf(a, b, _) => Seq(a, b)
    case EquivalentClasses(ops, _) => ops
    case DisjointClasses(ops, _) => ops
    case DisjointUnion(c, ops, _) => ClassExpr.Named(c) +: ops
    case Domain(_, d, _) => Seq(d)
    case Range(_, r: ClassExpr, _) => Seq(r)
    case HasKey(c, _, _) => Seq(c)
    case ClassAssertion(c, _, _) => Seq(c)
    case _ => Nil
  }
}

private[owl] object OwlAxiomIndex {

  /** Named classes in an expression, nested ones included. */
  def classesIn(e: Filler): Seq[String] = e match {
    case ClassExpr.Named(iri) => Seq(iri)
    case ClassExpr.IntersectionOf(ops) => ops.flatMap(classesIn)
    case ClassExpr.UnionOf(ops) => ops.flatMap(classesIn)
    case ClassExpr.ComplementOf(op) => classesIn(op)
    case ClassExpr.SomeValuesFrom(_, f) => classesIn(f)
    case ClassExpr.AllValuesFrom(_, f) => classesIn(f)
    case ClassExpr.Cardinality(_, _, _, f) => f.toSeq.flatMap(classesIn)
    case _ => Nil
  }

  /** Properties restricted in an expression. The flag is true for a data restriction. */
  def propertiesIn(e: Filler): Seq[(String, Boolean)] = e match {
    case ClassExpr.IntersectionOf(ops) => ops.flatMap(propertiesIn)
    case ClassExpr.UnionOf(ops) => ops.flatMap(propertiesIn)
    case ClassExpr.ComplementOf(op) => propertiesIn(op)
    case ClassExpr.SomeValuesFrom(p, f) => (p.iri, f.isInstanceOf[DataRange]) +: propertiesIn(f)
    case ClassExpr.AllValuesFrom(p, f) => (p.iri, f.isInstanceOf[DataRange]) +: propertiesIn(f)
    case ClassExpr.HasValue(p, v) => Seq(p.iri -> !v.isInstanceOf[Iri])
    case ClassExpr.HasSelf(p) => Seq(p.iri -> false)
    case ClassExpr.Cardinality(_, _, p, f) =>
      (p.iri, f.exists(_.isInstanceOf[DataRange])) +: f.toSeq.flatMap(propertiesIn)
    case _ => Nil
  }

  def datatypesIn(r: Filler): Seq[String] = r match {
    case DataRange.Datatype(iri) => Seq(iri)
    case DataRange.Restriction(iri, _) => Seq(iri)
    case DataRange.UnionOf(ops) => ops.flatMap(datatypesIn)
    case DataRange.IntersectionOf(ops) => ops.flatMap(datatypesIn)
    case DataRange.ComplementOf(op) => datatypesIn(op)
    case ClassExpr.SomeValuesFrom(_, f) => datatypesIn(f)
    case ClassExpr.AllValuesFrom(_, f) => datatypesIn(f)
    case ClassExpr.Cardinality(_, _, _, f) => f.toSeq.flatMap(datatypesIn)
    case ClassExpr.IntersectionOf(ops) => ops.flatMap(datatypesIn)
    case ClassExpr.UnionOf(ops) => ops.flatMap(datatypesIn)
    case _ => Nil
  }
}

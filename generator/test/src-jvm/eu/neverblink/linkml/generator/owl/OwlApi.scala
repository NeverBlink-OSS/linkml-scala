package eu.neverblink.linkml.generator.owl

import org.semanticweb.HermiT.{Configuration, ReasonerFactory}
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.formats.TurtleDocumentFormat
import org.semanticweb.owlapi.io.StringDocumentSource
import org.semanticweb.owlapi.model.{IRI, MissingImportHandlingStrategy, OWLObject, OWLOntology}
import org.semanticweb.owlapi.profiles.{OWL2DLProfile, OWLProfileViolation}
import org.semanticweb.owlapi.profiles.violations.{IllegalPunning, UseOfNonAbsoluteIRI}

import scala.jdk.CollectionConverters.*

/** Checks generated OWL with outside tools: the OWL API and the HermiT reasoner. */
object OwlApi {

  private val importPattern = "<http://www.w3.org/2002/07/owl#imports>\\s*<([^>]+)>".r

  /** Parses Turtle or N-Triples.
    */
  def load(turtle: String): OWLOntology = {
    val manager = OWLManager.createOWLOntologyManager()
    val imports = importPattern.findAllMatchIn(turtle).map(m => IRI.create(m.group(1))).toSeq
    manager.setOntologyLoaderConfiguration(
      imports.foldLeft(manager.getOntologyLoaderConfiguration)(_.addIgnoredImport(_))
        .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
        .setFollowRedirects(false),
    )
    manager.loadOntologyFromOntologyDocument(
      StringDocumentSource(
        turtle,
        IRI.create("urn:linkml-scala:test"),
        TurtleDocumentFormat(),
        null,
      ),
    )
  }

  /** Skips relative-IRI reports for blank nodes in ontology annotations, such as the `sh:declare`
    * of each prefix. The OWL API reads those wrongly; the RDF is fine.
    */
  def dlViolations(ontology: OWLOntology): Seq[String] = violations(ontology).map(_.toString)

  /** Like [[dlViolations]], with the IRIs each violation is about. */
  def dlViolationsAbout(ontology: OWLOntology): Seq[(String, Set[String])] =
    violations(ontology).map(v => v.toString -> about(v.getExpression))

  private def violations(ontology: OWLOntology): Seq[OWLProfileViolation] =
    OWL2DLProfile().checkOntology(ontology).getViolations.asScala.filterNot {
      case v: UseOfNonAbsoluteIRI => v.toString.contains("_:genid")
      case _ => false
    }.toSeq

  private def about(subject: Any): Set[String] = subject match {
    case iri: IRI => Set(iri.toString)
    case o: OWLObject => o.signature().iterator().asScala.map(_.getIRI.toString).toSet
    case _ => Set.empty
  }

  /** Properties used both as object and as data properties. In instance data this means a literal
    * where the ontology expects a link, or the other way around.
    */
  def misusedProperties(ontology: OWLOntology): Seq[String] =
    OWL2DLProfile().checkOntology(ontology).getViolations.asScala.collect {
      case v: IllegalPunning => v.toString
    }.toSeq

  /** Datatypes HermiT doesn't support, such as `xsd:date`, accept any value instead of failing. */
  def isConsistent(ontology: OWLOntology): Boolean = {
    val config = Configuration()
    config.ignoreUnsupportedDatatypes = true
    val reasoner = ReasonerFactory().createReasoner(ontology, config)
    try reasoner.isConsistent
    finally reasoner.dispose()
  }
}

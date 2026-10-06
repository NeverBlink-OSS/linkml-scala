package eu.neverblink.linkml.rdf

import org.eclipse.rdf4j.model.impl.{LinkedHashModel, SimpleValueFactory}
import org.eclipse.rdf4j.model.{Model, Resource as Rdf4jResource, Value}

/** Our triples as an RDF4J model, to compare graphs with RDF4J's. */
object Rdf4jModels {
  private val vf = SimpleValueFactory.getInstance()

  def toModel(triples: Seq[Triple]): Model = {
    val model = new LinkedHashModel
    triples.foreach { t =>
      model.add(toResource(t.subj), vf.createIRI(t.pred.value), toValue(t.obj))
    }
    model
  }

  private def toResource(res: Resource): Rdf4jResource = res match {
    case Iri(value) => vf.createIRI(value)
    case b: AnyBlankNode => vf.createBNode(b.id)
  }

  private def toValue(node: Node): Value = node match {
    case r: Resource => toResource(r)
    case LanguageLiteral(value, lang) => vf.createLiteral(value, lang)
    case Literal(value, datatype) => vf.createLiteral(value, vf.createIRI(datatype.value))
  }
}

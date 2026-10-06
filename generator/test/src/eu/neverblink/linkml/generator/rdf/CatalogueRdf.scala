package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.rdf.{RdfSink, TurtleParser}
import eu.neverblink.linkml.tests.ModelCatalogue

/** The RDF in the model catalogue: what the RDF generators make of every model, and the Turtle
  * files of its instances.
  */
object CatalogueRdf {

  /** Some instance files use relative IRIs. */
  val InstanceBase = "https://example.org/data/"

  /** Something that pushes RDF into a sink, afresh on every call.
    *
    * @param turtle
    *   the Turtle document it was read from, for an instance file
    */
  final case class Source(name: String, write: RdfSink => Unit, turtle: Option[String] = None)

  def sources(entry: ModelCatalogue.Entry): Seq[Source] = {
    val generated = Seq(
      Source("generated as SHACL", sink => ShaclGenerator(using entry.model).generate(sink)),
      Source("generated as RDFS", sink => RdfsGenerator(using entry.model).generate(sink)),
    )
    val instances =
      entry.validInstances.map("valid" -> _) ++ entry.invalidInstances.map("invalid" -> _)
    val files = for
      (kind, instance) <- instances.distinct
      (file, content) <- instance.turtle.map("data.ttl" -> _) ++
        instance.context.map("context.ttl" -> _) ++
        instance.additionalFiles.filter(_._1.endsWith(".ttl"))
    yield Source(
      s"read from '$kind/${instance.name}/$file'",
      sink => TurtleParser.parse(content, sink, Some(InstanceBase)),
      Some(content),
    )
    generated ++ files
  }
}

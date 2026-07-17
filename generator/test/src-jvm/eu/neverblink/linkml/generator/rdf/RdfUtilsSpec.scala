package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.generator.shacl.Shacl
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RdfUtilsSpec extends AnyWordSpec, Matchers {
  "RdfUtils" should {
    "serialize RDF model to string" in {
      RdfUtils.toTurtle { sink =>
        sink.namespace("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
        sink.namespace("sh", "http://www.w3.org/ns/shacl#")
        sink.namespace("xsd", "http://www.w3.org/2001/XMLSchema#")
        sink.triple(
          Iri("https://neverblink.eu/linkml/shacl/test/SomeClass"),
          Rdf.`type`,
          Shacl.NodeShape,
        )
      } shouldBe
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape .
          |""".stripMargin
    }
  }
}

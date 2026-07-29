package eu.neverblink.linkml.generator.graphql

import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GraphQlGeneratorSpec extends AnyWordSpec, Matchers {
  "blep" in {
    given SchemaView = ModelCatalogue.inheritance.model

    GraphQlGenerator().serialize() shouldBe "blep"
  }
}

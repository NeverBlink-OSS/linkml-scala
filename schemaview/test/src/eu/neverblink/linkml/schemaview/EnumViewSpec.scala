package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import eu.neverblink.linkml.runtime.*

class EnumViewSpec extends AnyWordSpec, Matchers {
  "EnumView" should {
    val sv = SchemaView.loadSchemaViewFromUri("linkml:meta")

    "provide meaning mappings" in {
      val pv_formula_options = sv.enums("relational_role_enum")
      pv_formula_options.toMeaning("SUBJECT") shouldBe Curie("rdf:subject")
      pv_formula_options.fromMeaning(Curie("rdf:subject")) shouldBe "SUBJECT"
    }
    "provide fallback with default prefix for missing meanings" in {
      val pv_formula_options = sv.enums("pv_formula_options")
      pv_formula_options.toMeaning("CODE") shouldBe Uri("https://w3id.org/linkml/CODE")
      pv_formula_options.fromMeaning(Uri("https://w3id.org/linkml/CODE")) shouldBe "CODE"
    }
  }
}

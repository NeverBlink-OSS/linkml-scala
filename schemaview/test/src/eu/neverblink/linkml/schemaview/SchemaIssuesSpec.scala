package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.validation.IssueSeverity
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SchemaIssuesSpec extends AnyWordSpec, Matchers {
  "SchemaIssues.conversionError" should {
    "give why a schema or ontology can't be converted as an issue" in {
      val issue =
        SchemaIssues.conversionError("The IRI <a b> isn't valid").infer()

      issue.issueType shouldBe "SchemaConversionError"
      issue.severity shouldBe IssueSeverity.Fatal
      issue.reason shouldBe "The IRI <a b> isn't valid"
      issue.message shouldBe Some("The IRI <a b> isn't valid")
    }
  }
}

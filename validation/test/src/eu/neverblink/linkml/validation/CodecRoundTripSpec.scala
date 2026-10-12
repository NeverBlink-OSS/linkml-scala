package eu.neverblink.linkml.validation

import eu.neverblink.linkml.runtime.{Curie, Uri}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.*

/** Round-trips a [[SchemaValidationReport]] to YAML and back.
  */
class CodecRoundTripSpec extends AnyWordSpec, Matchers {

  private val location = IssueLocation(
    schemaId = Some(Uri("https://neverblink.eu/test/")),
    jsonPointer = Some("/classes/SomeClass"),
  )

  private val report = SchemaValidationReport(
    issues = Seq(
      UnknownReference(location = location, referenceValue = "Foo"),
      NoTreeRootClass(location = location),
      InvalidUriOrCurie(
        location = location.copy(jsonPointer = Some("/classes/SomeClass/exact_mappings/0")),
        uriOrCurie = Curie("not a curie!"),
      ),
    ),
    validationRunId = Some("test-run"),
  )

  "Codec" should {
    "write the issue_type designator for every issue" in {
      val yaml = Codec.codec.encode(report).asYaml
      yaml.should(include("issue_type: UnknownReference"))
      yaml.should(include("issue_type: NoTreeRootClass"))
      yaml.should(include("issue_type: InvalidUriOrCurie"))
    }

    "recover the concrete issue types when reading a report back" in {
      val yaml = Codec.codec.encode(report).asYaml
      val decoded = Codec.codec.decode(parseYaml(yaml).toOption.get)
      decoded.shouldBe(report)
      decoded.issues.map(_.getClass).shouldBe(
        Seq(
          classOf[UnknownReference],
          classOf[NoTreeRootClass],
          classOf[InvalidUriOrCurie],
        ),
      )
    }

    "reject a report whose issue has an unknown issue_type" in {
      val yaml =
        """issues:
          |  - issue_type: NotAnIssueType
          |    location:
          |      schema_id: https://neverblink.eu/test/
          |""".stripMargin
      val error = intercept[Throwable](Codec.codec.decode(parseYaml(yaml).toOption.get))
      error.getMessage.should(include("a known value of type designator 'issue_type'"))
    }
  }
}

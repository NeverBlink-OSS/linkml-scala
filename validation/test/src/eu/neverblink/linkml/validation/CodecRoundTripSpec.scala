package eu.neverblink.linkml.validation

import eu.neverblink.linkml.runtime.{Curie, Uri}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.*

/** Round-trips a [[SchemaValidationReport]] to YAML and back.
  */
class CodecRoundTripSpec extends AnyWordSpec, Matchers {

  private val location = IssueLocationImpl(
    schemaId = Some(Uri("https://neverblink.eu/test/")),
    jsonPointer = Some("/classes/SomeClass"),
  )

  private val report = SchemaValidationReportImpl(
    issues = Seq(
      UnknownReferenceImpl(location = location, referenceValue = "Foo"),
      NoTreeRootClassImpl(location = location),
      InvalidUriOrCurieImpl(
        location = location,
        elementName = "SomeClass",
        elementType = "class",
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
          classOf[UnknownReferenceImpl],
          classOf[NoTreeRootClassImpl],
          classOf[InvalidUriOrCurieImpl],
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

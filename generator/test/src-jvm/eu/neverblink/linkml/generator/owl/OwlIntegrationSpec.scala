package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.rdf.RdfFormat
import eu.neverblink.linkml.tests.ModelCatalogue.InstanceInFormats
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Checks the OWL for every catalogue model with outside tools. The OWL must be in OWL 2 DL, valid
  * instances must be consistent with it, and invalid ones must not. OWL can't catch many invalid
  * instances: see [[OwlIntegrationSpec.skipInstances]].
  */
class OwlIntegrationSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {

  override val skipInstances: Map[(String, String), String] = OwlIntegrationSpec.skipInstances

  "OwlGenerator" should {
    for entry <- ModelCatalogue.all do
      s"generate OWL for model '${entry.name}'" when {
        lazy val nt =
          OwlGenerator(using entry.model).serialize(OwlGenerator.Options(format = RdfFormat.nt))

        "the OWL is in OWL 2 DL" in {
          withClue(s"$nt\n") {
            OwlApi.dlViolations(OwlApi.load(nt)) shouldBe empty
          }
        }

        def withData(instance: InstanceInFormats) =
          OwlApi.load(nt + "\n" + instance.turtle.get + instance.context.getOrElse(""))

        for valid <- entry.validInstances.filter(_.turtle.isDefined).distinct do
          s"valid instance '${valid.name}' is consistent with it" in {
            processSkip(entry, valid)
            val ontology = withData(valid)
            withClue(s"${valid.turtle.get}\n$nt\n") {
              OwlApi.misusedProperties(ontology) shouldBe empty
              OwlApi.isConsistent(ontology) shouldBe true
            }
          }

        for invalid <- entry.invalidInstances.filter(_.turtle.isDefined).distinct do
          s"invalid instance '${invalid.name}' is rejected by it" in {
            processSkip(entry, invalid)
            val ontology = withData(invalid)
            withClue(s"${invalid.turtle.get}\n$nt\n") {
              val rejected =
                OwlApi.misusedProperties(ontology).nonEmpty || !OwlApi.isConsistent(ontology)
              rejected shouldBe true
            }
          }
      }
  }
}

object OwlIntegrationSpec {

  private val openWorld =
    "OWL does not take a value that is not there as missing, so a required value or a minimum " +
      "count is never violated (not a bug)"
  private val unknownProperty =
    "OWL allows properties that the ontology does not describe on any class (not a bug)"
  private val noUniqueNames =
    "OWL does not assume that different IRIs name different things, so an IRI outside the enum " +
      "may still be one of its values (not a bug)"

  private val anyLiteral =
    "BUG: A slot with range Any takes literals as well as links, and an OWL property cannot take both"

  /** Invalid instances OWL can't tell from valid ones, and valid ones it can't handle. */
  val skipInstances: Map[(String, String), String] = Map(
    "cardinality" -> "one0" -> openWorld,
    "cardinality" -> "atLeastOne0" -> openWorld,
    "cardinalityExplicit" -> "oneToThreeMissing" -> openWorld,
    "cardinalityExplicit" -> "exactlyTwoTooFew" -> openWorld,
    "basic" -> "missing" -> openWorld,
    "basic" -> "empty" -> openWorld,
    "anything" -> "missing" -> openWorld,
    "typeDesignator2" -> "wrongSlotForType" -> s"$openWorld, and $unknownProperty",
    "aliases" -> "slotNameInsteadOfUri" -> unknownProperty,
    "unionRangeReference" -> "baseOnly" ->
      "OWL infers that a value is of the range's class rather than rejecting one not said to be",
    "enum" -> "badType" -> noUniqueNames,
    "typed" -> "notDate" -> "HermiT does not support xsd:date, so it cannot reject a value that is not a date",
    // Valid instances
    "anything" -> "atomic" -> anyLiteral,
    "anything" -> "complex" -> anyLiteral,
  )
}

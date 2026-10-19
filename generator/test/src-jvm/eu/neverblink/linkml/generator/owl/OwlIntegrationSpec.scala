package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.RdfGeneratorBase.RdfFormat
import eu.neverblink.linkml.tests.ModelCatalogue.InstanceInFormats
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Checks the OWL for every catalogue model with outside tools. The OWL must be in OWL 2 DL, valid
  * instances must be consistent with it, and invalid ones must not. OWL can't catch many invalid
  * instances: see [[OwlIntegrationSpec.skipInstances]].
  */
class OwlIntegrationSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {

  override val skipModels: Map[String, String] = OwlIntegrationSpec.skipModels
  override val skipInstances: Map[(String, String), String] = OwlIntegrationSpec.skipInstances

  "OwlGenerator" should {
    for entry <- ModelCatalogue.all do
      s"generate OWL for model '${entry.name}'" when {
        lazy val nt =
          OwlGenerator(using entry.model).serialize(OwlGenerator.Options(format = RdfFormat.nt))

        "the OWL is in OWL 2 DL" in {
          processSkip(entry.name, "")
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

  /** Models whose OWL is wrong, with the reason. */
  val skipModels: Map[String, String] = {
    val slotUsageUri =
      "BUG: A `slot_uri` set by a `slot_usage` is not declared as an OWL property, and the class " +
        "gets the range of the slot definition for it"
    Map(
      "importedOverride" -> slotUsageUri,
      "mixinFacets" -> slotUsageUri,
    )
  }

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
    "allOf" -> "matchesFirstOnly" -> "Not yet implemented: `all_of`",
    "exactlyOneOf" -> "matchesBoth" -> "Not yet implemented: `exactly_one_of`",
    "exactlyOneOf" -> "matchesNone" -> "Not yet implemented: `exactly_one_of`",
    "noneOf" -> "matchesMember" -> "Not yet implemented: `none_of`",
    "allOfInheritance" -> "failsBase" -> "Not yet implemented: `all_of`",
    "allOfInheritance" -> "failsSlotUsage" -> "Not yet implemented: `all_of`",
    "exactlyOneOfInheritance" -> "matchesBothInBase" -> "Not yet implemented: `exactly_one_of`",
    "exactlyOneOfInheritance" -> "matchesNone" -> "Not yet implemented: `exactly_one_of`",
    "noneOfInheritance" -> "matchesBase" -> "Not yet implemented: `none_of`",
    "noneOfInheritance" -> "matchesSlotUsage" -> "Not yet implemented: `none_of`",
    "anyOfClassRanges" -> "reportFromBase" ->
      "OWL infers that the value is also a `Command`, since `Command` and `Report` are not disjoint (not a bug)",
    "allOfMixins" -> "failsBase" -> "Not yet implemented: `all_of`",
    "allOfMixins" -> "failsMixin" -> "Not yet implemented: `all_of`",
    "noneOfMixins" -> "matchesBase" -> "Not yet implemented: `none_of`",
    "noneOfMixins" -> "matchesMixin" -> "Not yet implemented: `none_of`",
    "exactlyOneOfMixins" -> "matchesBothInBase" -> "Not yet implemented: `exactly_one_of`",
    "exactlyOneOfMixins" -> "matchesNoneInMixin" -> "Not yet implemented: `exactly_one_of`",
    "exactlyOneOfWidening" -> "matchesBothInBase" -> "Not yet implemented: `exactly_one_of`",
    "exactlyOneOfWidening" -> "matchesNoneInSlotUsage" -> "Not yet implemented: `exactly_one_of`",
    "allOfNoneOfChain" -> "failsBaseAllOf" -> "Not yet implemented: `all_of`",
    "allOfNoneOfChain" -> "failsBaseNoneOf" -> "Not yet implemented: `none_of`",
    "allOfNoneOfChain" -> "failsChildNoneOf" -> "Not yet implemented: `none_of`",
    "allOfNoneOfChain" -> "failsGrandchildNoneOf" -> "Not yet implemented: `none_of`",
    "nestedExpressions" -> "largeInteger" -> "Not yet implemented: `none_of` in `any_of` members",
    "memberCardinality" -> "oneInteger" -> openWorld,
    "memberCardinality" -> "integerAndString" ->
      "Not yet implemented: cardinality in `any_of` members",
    "memberCardinality" -> "twoStrings" -> "Not yet implemented: cardinality in `any_of` members",
    "requiredOverride" -> "missingTitle" -> openWorld,
    "requiredOverride" -> "missingName" -> openWorld,
    "rangeOverride" -> "basePart" ->
      s"OWL infers that the part is also a `CarPart` rather than rejecting it, and $openWorld",
    // Valid instances
    "multivaluedOverride" -> "many" -> (
      "The parent class allows one value, and in OWL a subclass can't allow more, so a " +
        "`slot_usage` that makes a slot multivalued has no effect"
    ),
    "anything" -> "atomic" -> anyLiteral,
    "anything" -> "complex" -> anyLiteral,
  )
}

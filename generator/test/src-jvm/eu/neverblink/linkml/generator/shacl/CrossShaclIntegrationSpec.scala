package eu.neverblink.linkml.generator.shacl

import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.sail.shacl.ShaclValidator
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import os.Path

class CrossShaclIntegrationSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  val pwd: Path = Option(System.getenv("MILL_WORKSPACE_ROOT"))
    .map(Path(_))
    .getOrElse(os.pwd)
  val generatedModelsDir: Path = pwd / ".generated" / "tests" / "resources"
  val enabled: Boolean = (System.getenv("CI") != null) || os.exists(generatedModelsDir)
  override val skipModels: Map[String, String] = Map(
    "anything" -> "Metamodel extended_types.yaml is not bundled",
    "typeDesignator" -> "Metamodel extended_types.yaml is not bundled",
    "unionRange" -> "Metamodel extended_types.yaml is not bundled",
    "explicitInlineImplicitlyAsSimpleDict" -> "LinkML-py SHACL generator omits constraints on default-ranges",
    "curie" -> "LinkML-py treats CURIEs as literals instead of expanding them",
  )
  override val skipInstances: Map[(String, String), String] = Map(
    ("implicitPrefix", "slotDefined") -> "LinkML-py incorrectly does not lift these instances",
  )
  val vf: SimpleValueFactory = SimpleValueFactory.getInstance()
  "LinkMl Python SHACL generator" should {
    for entry <- ModelCatalogue.all do
      s"generate SHACL for model '${entry.model.root.name}'" when {
        lazy val path = Path(entry.path).segments.toSeq
        lazy val ttl = os.read(generatedModelsDir / path / "shacl.ttl")
        lazy val validator = ShaclValidator.builder().withShapes(ttl, RDFFormat.TURTLE).build()

        for valid <- entry.validInstances.filter(_.turtle.isDefined) do
          s"valid instance '${valid.name}'" in {
            assume(enabled)
            processSkip(entry, valid)
            val res = validator.validate(
              valid.turtle.get + valid.context.getOrElse(""),
              RDFFormat.TURTLE,
            )
            withClue(res.getValidationResult) {
              res.conforms() shouldBe true
            }
          }
        for invalid <- entry.invalidInstances.filter(_.turtle.isDefined) do
          s"invalid data '${invalid.name}'" in {
            assume(enabled)
            processSkip(entry, invalid)
            val res = validator.validate(
              invalid.turtle.get + invalid.context.getOrElse(""),
              RDFFormat.TURTLE,
            )
            res.conforms() shouldBe false
          }
      }
  }
}

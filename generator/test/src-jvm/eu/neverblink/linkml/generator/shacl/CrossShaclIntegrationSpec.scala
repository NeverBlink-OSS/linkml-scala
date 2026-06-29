package eu.neverblink.linkml.generator.shacl

import eu.neverblink.linkml.tests.ModelCatalogue
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.sail.shacl.ShaclValidator
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import os.Path

class CrossShaclIntegrationSpec extends AnyWordSpec, Matchers {
  val pwd: Path = Option(System.getenv("MILL_WORKSPACE_ROOT"))
    .map(Path(_))
    .getOrElse(os.pwd)
  val generatedModelsDir: Path = pwd / ".generated" / "tests" / "resources"
  val enabled: Boolean = (System.getenv("CI") != null) || os.exists(generatedModelsDir)
  val skipModelsShaclPy: Seq[String] = Seq(
    // Metamodel extended_types.yaml is not bundled
    "anything",
    "typeDesignator",
    "unionRange",
    // LinkML-py SHACL generator omits constraints on default-ranges
    "explicitInlineImplicitlyAsSimpleDict",
    // LinkML-py treats CURIEs as literals instead of expanding them
    "curie",
  )
  val vf: SimpleValueFactory = SimpleValueFactory.getInstance()
  "LinkMl Python Shacl generator" should {
    for entry <- ModelCatalogue.all do
      s"generate Json Schema for model '${entry.model.root.name}'" when {
        lazy val path = Path(entry.path).segments.toSeq
        lazy val ttl = os.read(generatedModelsDir / path / "shacl.ttl")
        lazy val validator = ShaclValidator.builder().withShapes(ttl, RDFFormat.TURTLE).build()

        for valid <- entry.validInstances.filter(_.turtle.isDefined).distinct do
          s"valid instance '${valid.name}'" in {
            assume(enabled && !skipModelsShaclPy.contains(entry.model.root.name))
            val res =
              validator.validate(valid.turtle.get + valid.context.getOrElse(""), RDFFormat.TURTLE)
            withClue(res.getValidationResult) {
              res.conforms() shouldBe true
            }
          }
        for invalid <- entry.invalidInstances.filter(_.turtle.isDefined).distinct do
          s"invalid data '${invalid.name}'" in {
            assume(enabled && !skipModelsShaclPy.contains(entry.model.root.name))
            val res = validator.validate(invalid.turtle.get, RDFFormat.TURTLE)
            res.conforms() shouldBe false
          }
      }
  }
}

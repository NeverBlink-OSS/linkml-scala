package eu.neverblink.linkml.generator.shacl

import eu.neverblink.linkml.rdf.RdfUtils
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.rio.RDFFormat
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.eclipse.rdf4j.sail.shacl.ShaclValidator

class ShaclIntegrationSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  override val skipModels: Map[String, String] = ShaclGeneratorSpec.skipModels
  override val skipInstances: Map[(String, String), String] = Map(
    // ShaclGeneratorSpec checks the generated `sh:xone` instead
    ("exactlyOneOf", "matchesBoth") -> "RDF4J does not enforce `sh:xone`",
    ("exactlyOneOf", "matchesNone") -> "RDF4J does not enforce `sh:xone`",
    ("exactlyOneOfInheritance", "matchesBothInBase") -> "RDF4J does not enforce `sh:xone`",
    ("exactlyOneOfInheritance", "matchesNone") -> "RDF4J does not enforce `sh:xone`",
  )
  val vf: SimpleValueFactory = SimpleValueFactory.getInstance()
  "ShaclGenerator" should {
    for entry <- ModelCatalogue.all do
      s"generate SHACL for model '${entry.model.root.name}'" when {

        lazy val ttl = RdfUtils.toTurtle(ShaclGenerator(using entry.model).generate(_))
        lazy val validator = ShaclValidator.builder().withShapes(ttl, RDFFormat.TURTLE).build()

        for valid <- entry.validInstances.filter(_.turtle.isDefined).distinct do
          s"valid instance '${valid.name}'" in {
            processSkip(entry, valid)
            val res =
              validator.validate(valid.turtle.get + valid.context.getOrElse(""), RDFFormat.TURTLE)
            withClue(res.getValidationResult) {
              res.conforms() shouldBe true
            }
          }
        for invalid <- entry.invalidInstances.filter(_.turtle.isDefined).distinct do
          s"invalid data '${invalid.name}'" in {
            processSkip(entry, invalid)
            val res = validator.validate(invalid.turtle.get, RDFFormat.TURTLE)
            res.conforms() shouldBe false
          }
      }
  }
}

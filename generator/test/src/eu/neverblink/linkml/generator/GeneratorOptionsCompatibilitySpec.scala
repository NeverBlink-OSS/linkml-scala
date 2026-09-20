package eu.neverblink.linkml.generator

import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.frictionless.FrictionlessGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.ossie.OssieGenerator
import eu.neverblink.linkml.generator.rdf.{RdfFormat, RdfOptions}
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.translation.TranslationGenerator
import eu.neverblink.linkml.generator.util.{JsonOutputFormat, PruningMode}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

// Handwritten clients preserve the API independently of the option schemas.
class GeneratorOptionsCompatibilitySpec extends AnyWordSpec, Matchers {

  private val root: PruningMode = PruningMode.treeRoot(Some("Person"))

  "Generator options" should {
    "preserve JSON Schema defaults and case-class API" in {
      val defaults: JsonSchemaGenerator.Options = JsonSchemaGenerator.Options()
      val fields: (Boolean, Option[String], Option[String], Int, String, Boolean) = (
        defaults.open,
        defaults.treeRoot,
        defaults.treeRootInlineType,
        defaults.indentationStep,
        defaults.metadataLanguage,
        defaults.includeNull,
      )
      fields shouldBe ((false, None, None, 2, "en", false))

      val options = JsonSchemaGenerator.Options(
        open = true,
        treeRoot = Some("Person"),
        treeRootInlineType = Some("Container"),
        indentationStep = 0,
        metadataLanguage = "",
        includeNull = false,
      )
      options shouldBe JsonSchemaGenerator.Options(
        true,
        Some("Person"),
        Some("Container"),
        0,
        "",
        false,
      )
      val JsonSchemaGenerator.Options(open, treeRoot, inlineType, indentation, language, nulls) =
        options.copy(treeRoot = None, includeNull = true)
      (open, treeRoot, inlineType, indentation, language, nulls) shouldBe
        ((true, None, Some("Container"), 0, "", true))
    }

    "preserve Scala defaults and case-class API" in {
      val defaults: ScalaGenerator.Options = ScalaGenerator.Options()
      val fields: (String, Boolean, String) =
        (defaults.`package`, defaults.generateEmitPrefixes, defaults.metadataLanguage)
      fields shouldBe (("eu.neverblink.linkml.metamodel", true, "en"))

      val options = ScalaGenerator.Options(
        `package` = "",
        generateEmitPrefixes = false,
        metadataLanguage = "de",
      )
      options shouldBe ScalaGenerator.Options("", false, "de")
      val ScalaGenerator.Options(pkg, prefixes, language) = options.copy(metadataLanguage = "")
      (pkg, prefixes, language) shouldBe (("", false, ""))
    }

    "preserve LinkML defaults and case-class API" in {
      val defaults: LinkMlGenerator.Options = LinkMlGenerator.Options()
      val fields: (PruningMode, Boolean, JsonOutputFormat) =
        (defaults.pruningMode, defaults.skipClassDerivation, defaults.outputFormat)
      fields shouldBe ((PruningMode.skip, false, JsonOutputFormat.yaml))

      val options = LinkMlGenerator.Options(
        pruningMode = root,
        skipClassDerivation = true,
        outputFormat = JsonOutputFormat.json,
      )
      options shouldBe LinkMlGenerator.Options(root, true, JsonOutputFormat.json)
      val LinkMlGenerator.Options(pruning, skipDerivation, format) =
        options.copy(pruningMode = PruningMode.skip)
      (pruning, skipDerivation, format) shouldBe ((PruningMode.skip, true, JsonOutputFormat.json))
    }

    "preserve GraphQL defaults and case-class API" in {
      val defaults: GraphQlGenerator.Options = GraphQlGenerator.Options()
      val fields: (PruningMode, String) = (defaults.pruningMode, defaults.metadataLanguage)
      fields shouldBe ((PruningMode.schemaRoot, "en"))

      val options = GraphQlGenerator.Options(pruningMode = root, metadataLanguage = "")
      options shouldBe GraphQlGenerator.Options(root, "")
      val GraphQlGenerator.Options(pruning, language) = options.copy(metadataLanguage = "de")
      (pruning, language) shouldBe ((root, "de"))
    }

    "preserve ER diagram defaults and case-class API" in {
      val defaults: ErDiagramGenerator.Options = ErDiagramGenerator.Options()
      val fields: (PruningMode, Boolean) = (defaults.pruningMode, defaults.optionalMarker)
      fields shouldBe ((PruningMode.schemaRoot, true))

      val options = ErDiagramGenerator.Options(
        pruningMode = PruningMode.treeRoot(None),
        optionalMarker = false,
      )
      options shouldBe ErDiagramGenerator.Options(PruningMode.treeRoot(None), false)
      val ErDiagramGenerator.Options(pruning, marker) = options.copy(pruningMode = root)
      (pruning, marker) shouldBe ((root, false))
    }

    "preserve Frictionless defaults and case-class API" in {
      val defaults: FrictionlessGenerator.Options = FrictionlessGenerator.Options()
      val fields: (PruningMode, Boolean, String) =
        (defaults.pruningMode, defaults.skipClassesWithoutIdentifier, defaults.metadataLanguage)
      fields shouldBe ((PruningMode.skip, false, "en"))

      val options = FrictionlessGenerator.Options(
        pruningMode = root,
        skipClassesWithoutIdentifier = true,
        metadataLanguage = "",
      )
      options shouldBe FrictionlessGenerator.Options(root, true, "")
      val FrictionlessGenerator.Options(pruning, skipClasses, language) =
        options.copy(metadataLanguage = "de")
      (pruning, skipClasses, language) shouldBe ((root, true, "de"))
    }

    "preserve Ossie defaults and case-class API" in {
      val defaults: OssieGenerator.Options = OssieGenerator.Options()
      val fields: (PruningMode, JsonOutputFormat, String) =
        (defaults.pruningMode, defaults.outputFormat, defaults.metadataLanguage)
      fields shouldBe ((PruningMode.skip, JsonOutputFormat.yaml, "en"))

      val options = OssieGenerator.Options(
        pruningMode = root,
        outputFormat = JsonOutputFormat.json,
        metadataLanguage = "",
      )
      options shouldBe OssieGenerator.Options(root, JsonOutputFormat.json, "")
      val OssieGenerator.Options(pruning, format, language) =
        options.copy(outputFormat = JsonOutputFormat.yaml)
      (pruning, format, language) shouldBe ((root, JsonOutputFormat.yaml, ""))
    }

    "preserve Translation defaults and case-class API" in {
      val defaults: TranslationGenerator.Options = TranslationGenerator.Options()
      val fields: (String, Int) = (defaults.to, defaults.indentationStep)
      fields shouldBe (("base", 2))

      val options = TranslationGenerator.Options(to = "erdiagram", indentationStep = 0)
      options shouldBe TranslationGenerator.Options("erdiagram", 0)
      val TranslationGenerator.Options(target, indentation) = options.copy(to = "")
      (target, indentation) shouldBe (("", 0))
    }

    "preserve SHACL defaults and case-class API" in {
      val defaults: ShaclGenerator.Options = ShaclGenerator.Options()
      val fields: (Boolean, Boolean, RdfFormat) =
        (defaults.open, defaults.onlyClassesFromRootSchema, defaults.format)
      fields shouldBe ((false, false, RdfFormat.ttl))

      val options = ShaclGenerator.Options(
        open = true,
        onlyClassesFromRootSchema = false,
        format = RdfFormat.nt,
      )
      options shouldBe ShaclGenerator.Options(true, false, RdfFormat.nt)
      val ShaclGenerator.Options(open, onlyRoot, format) =
        options.copy(onlyClassesFromRootSchema = true)
      (open, onlyRoot, format) shouldBe ((true, true, RdfFormat.nt))
    }

    "preserve RDFS defaults and case-class API" in {
      val defaults: RdfsGenerator.Options = RdfsGenerator.Options()
      val fields: (Boolean, RdfFormat) = (defaults.onlyClassesFromRootSchema, defaults.format)
      fields shouldBe ((false, RdfFormat.ttl))

      val options = RdfsGenerator.Options(onlyClassesFromRootSchema = true, format = RdfFormat.nt)
      options shouldBe RdfsGenerator.Options(true, RdfFormat.nt)
      val RdfsGenerator.Options(onlyRoot, format) = options.copy(format = RdfFormat.ttl)
      (onlyRoot, format) shouldBe ((true, RdfFormat.ttl))
    }

    "retain the shared RDF options contract" in {
      val custom = new RdfOptions {
        def format: RdfFormat = RdfFormat.nt
      }
      val options: Seq[RdfOptions] = Seq(ShaclGenerator.Options(), RdfsGenerator.Options(), custom)
      options.map(_.format) shouldBe Seq(RdfFormat.ttl, RdfFormat.ttl, RdfFormat.nt)
    }
  }
}

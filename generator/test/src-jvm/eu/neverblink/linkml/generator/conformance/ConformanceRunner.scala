package eu.neverblink.linkml.generator.conformance

import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml
import Codec.manifestCodec
import com.networknt.schema.{InputFormat, SchemaRegistry}
import com.networknt.schema.dialect.Dialects
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.schemaview.SchemaView
import io.circe.ACursor
import org.scalatest.matchers.should.Matchers
import io.circe.parser.parse as parseJson
import eu.neverblink.linkml.schemaview.yaml
import os.Path

class ConformanceRunner extends AnyWordSpec, Matchers {
  val resources: Path = os.Path("/home/kacper/NeverBlink/linkml-scala/generator/test/resources")
  val mfStr: String = os.read(resources / "conformance/manifest.yaml")
  val manifest: ManifestImpl = manifestCodec.decode(
    parseYaml(mfStr).getOrElse(
      throw RuntimeException("invalid yaml in manifest"),
    ),
  )

  val sr: SchemaRegistry = SchemaRegistry.withDialect(Dialects.getDraft202012)

  s"test ${manifest.name}" should {
    for (test <- manifest.entries.values) {
      s"run ${test.name}" in {
        lazy val sv = SchemaView.loadSchemaViewFromUri(
          resources.toString + "/" + test.schema,
        ).getOrElse(fail("couldn't load schema"))

        val result = test.action match {
          case _: DeriveAction =>
            LinkMlGenerator(using sv).serialize(
              LinkMlGenerator.Options(outputFormat = LinkMlGenerator.OutputFormat.json),
            )
          case _: JsonSchemaGenerate =>
            JsonSchemaGenerator(using sv).serialize()
          case _: LintAction =>
            sv.lint().getOrElse("")
          case _: LoadAction =>
            sv should not be null
            ""
          case _ => ???
        }

        test.assertion match {
          case assertion: JsonPathAssertion =>
            val parsedSchema: ACursor = parseJson(result).toTry.get.hcursor
            val stepped = assertion.path.split('/').foldLeft(parsedSchema) { (schema, key) =>
              try {
                schema.downN(key.toInt)
              } catch {
                case _: NumberFormatException =>
                  schema.downField(key)
              }
            }
            if stepped.failed then fail(s"Could not access the value at ${assertion.path}")
            val resultValue = stepped.focus.get
            parseJson(
              JsonUtil.yamlToJson(assertion.value.yaml.toTry.get),
            ).toTry.get shouldBe resultValue
          case accepts: JsonSchemaAccepts =>
            sr.getSchema(result).validate(
              os.read(os.Path(resources.toString + "/" + accepts.instance)),
              InputFormat.JSON,
            ) shouldBe empty
          case rejects: JsonSchemaRejects =>
            sr.getSchema(result).validate(
              os.read(os.Path(resources.toString + "/" + rejects.instance)),
              InputFormat.JSON,
            ) should not be empty
          case _: LoadsAssertion =>
          case assertion: StringAssertion =>
            assertion.includes.foreach { part =>
              result should include(part)
            }
        }
      }
    }
  }
}

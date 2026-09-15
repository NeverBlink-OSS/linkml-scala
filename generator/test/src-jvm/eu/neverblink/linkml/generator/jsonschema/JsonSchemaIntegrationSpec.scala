package eu.neverblink.linkml.generator.jsonschema

import com.networknt.schema.{ExecutionConfig, ExecutionContext, InputFormat, SchemaRegistry}
import com.networknt.schema.dialect.Dialects
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class JsonSchemaIntegrationSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  val sr: SchemaRegistry = SchemaRegistry.withDialect(Dialects.getDraft202012)
  override val skipModels: Map[String, String] = JsonSchemaGeneratorSpec.skipModels

  "Json Schema generator" should {
    for includeNull <- Seq(false, true) do
      s"LNK-34 optional scalar nulls with includeNull=$includeNull" in {
        val options =
          if includeNull then JsonSchemaGenerator.Options(includeNull = true)
          else JsonSchemaGenerator.Options()

        val jsonSchema = JsonSchemaGenerator(using ModelCatalogue.basic.model).serialize(options)
        val schema = sr.getSchema(jsonSchema)

        def accepts(json: String): Boolean = schema.validate(json, InputFormat.JSON).isEmpty

        accepts("""{"some_other_slot": 1}""") shouldBe true
        accepts("""{"some_slot": "JellyBean", "some_other_slot": 1}""") shouldBe true
        accepts("""{"some_slot": 42, "some_other_slot": 1}""") shouldBe false
        accepts("""{}""") shouldBe false
        accepts("""{"some_other_slot": null}""") shouldBe false
        accepts("""{"some_slot": null, "some_other_slot": 1}""") shouldBe includeNull
      }

    for includeNull <- Seq(false, true) do
      //  The rule is that a dictionary entry may be null when its key is itself all required content
      s"LNK-34 compact dictionary entries with includeNull=$includeNull" in {
        val model =
          ModelCatalogue.inlines.explicitInlineImplicitlyAsCompactDict.model
        val options = JsonSchemaGenerator.Options(includeNull = includeNull)
        val schema = sr.getSchema(JsonSchemaGenerator(using model).serialize(options))

        def accepts(json: String): Boolean =
          schema.validate(json, InputFormat.JSON).isEmpty

        accepts("""{"some_slot": {"YES": {}}}""") shouldBe true
        accepts("""{"some_slot": {"YES": []}}""") shouldBe false
        accepts("""{"some_slot": null}""") shouldBe includeNull
        accepts("""{"some_slot": {"YES": null, "NO": null}}""") shouldBe includeNull
      }

    "LNK-34 respect required content in root dictionary entries" in {
      val options = JsonSchemaGenerator.Options(
        treeRootInlineType = Some("compact_dict"),
        includeNull = true,
      )

      val keyOnly = sr.getSchema(
        JsonSchemaGenerator(using
          ModelCatalogue.inlines.selfCompact1.model,
        ).serialize(options),
      )

      val requiredContent = sr.getSchema(
        JsonSchemaGenerator(using
          ModelCatalogue.inlines.selfCompact3Required.model,
        ).serialize(options),
      )

      keyOnly.validate("""{"YES": null}""", InputFormat.JSON) shouldBe empty
      keyOnly.validate("null", InputFormat.JSON) should not be empty

      requiredContent.validate("""{"YES": null}""", InputFormat.JSON) should not be empty
      requiredContent.validate("""{"YES": {}}""", InputFormat.JSON) should not be empty
      requiredContent.validate(
        """{"YES": {"some_slot": "a", "some_other_slot": "b"}}""",
        InputFormat.JSON,
      ) shouldBe empty
    }

    for includeNull <- Seq(false, true) do
      s"LNK-34 simple dictionary entries with includeNull=$includeNull" in {
        val model =
          ModelCatalogue.inlines.explicitInlineImplicitlyAsSimpleDict.model
        val options = JsonSchemaGenerator.Options(includeNull = includeNull)
        val schema = sr.getSchema(JsonSchemaGenerator(using model).serialize(options))

        def accepts(json: String): Boolean =
          schema.validate(json, InputFormat.JSON).isEmpty

        accepts("""{"some_slot": {"YES": "description"}}""") shouldBe true
        accepts(
          """{"some_slot": {"YES": {"other_slot": "description"}}}""",
        ) shouldBe true
        accepts("""{"some_slot": {"YES": {}}}""") shouldBe true
        accepts("""{"some_slot": {"YES": 42}}""") shouldBe false
        accepts("""{"some_slot": {"YES": null}}""") shouldBe includeNull
      }

    for includeNull <- Seq(false, true) do
      s"LNK-34 simple root dictionary requirements with includeNull=$includeNull" in {
        val options = JsonSchemaGenerator.Options(
          treeRootInlineType = Some("simple_dict"),
          includeNull = includeNull,
        )

        val optionalValue = sr.getSchema(
          JsonSchemaGenerator(using
            ModelCatalogue.inlines.selfSimple2.model,
          ).serialize(options),
        )

        val requiredValue = sr.getSchema(
          JsonSchemaGenerator(using
            ModelCatalogue.inlines.selfSimple2Required.model,
          ).serialize(options),
        )

        optionalValue.validate(
          """{"YES": null}""",
          InputFormat.JSON,
        ).isEmpty shouldBe includeNull

        optionalValue.validate("""{"YES": "value"}""", InputFormat.JSON) shouldBe empty
        optionalValue.validate("""{"YES": {}}""", InputFormat.JSON) shouldBe empty
        optionalValue.validate("null", InputFormat.JSON) should not be empty

        requiredValue.validate("""{"YES": null}""", InputFormat.JSON) should not be empty
        requiredValue.validate("""{"YES": {}}""", InputFormat.JSON) should not be empty
        requiredValue.validate("""{"YES": "value"}""", InputFormat.JSON) shouldBe empty
        requiredValue.validate(
          """{"YES": {"some_slot": "value"}}""",
          InputFormat.JSON,
        ) shouldBe empty
      }

    for includeNull <- Seq(false, true) do
      s"LNK-34 inline list nulls with includeNull=$includeNull" in {
        val model = ModelCatalogue.inlines.explicitInlineList.model
        val options = JsonSchemaGenerator.Options(includeNull = includeNull)
        val schema = sr.getSchema(JsonSchemaGenerator(using model).serialize(options))

        def accepts(json: String): Boolean =
          schema.validate(json, InputFormat.JSON).isEmpty

        accepts("""{}""") shouldBe true
        accepts("""{"some_slot": []}""") shouldBe true
        accepts("""{"some_slot": [{"id": "x"}]}""") shouldBe true
        accepts("""{"some_slot": null}""") shouldBe includeNull

        accepts("""{"some_slot": [null]}""") shouldBe false
        accepts("""{"some_slot": [{}]}""") shouldBe false
        accepts("""{"some_slot": {}}""") shouldBe false
      }

    for includeNull <- Seq(false, true) do
      s"LNK-34 required list presence with includeNull=$includeNull" in {
        val options = JsonSchemaGenerator.Options(includeNull = includeNull)
        val schema = sr.getSchema(
          JsonSchemaGenerator(using ModelCatalogue.cardinality.model).serialize(options),
        )

        def accepts(json: String): Boolean =
          schema.validate(json, InputFormat.JSON).isEmpty

        accepts("""{"one": "x", "at_least_one": ["a"]}""") shouldBe true
        accepts(
          """{"one": "x", "at_least_one": ["a"], "zero_or_more": null}""",
        ) shouldBe includeNull

        accepts("""{"one": "x"}""") shouldBe false
        accepts("""{"one": "x", "at_least_one": null}""") shouldBe false
        accepts("""{"one": "x", "at_least_one": [null]}""") shouldBe false
        accepts(
          """{"one": "x", "at_least_one": ["a"], "zero_or_more": [null]}""",
        ) shouldBe false
      }

    for includeNull <- Seq(false, true) do
      s"LNK-34 list size constraints with includeNull=$includeNull" in {
        val options = JsonSchemaGenerator.Options(includeNull = includeNull)
        val schema = sr.getSchema(
          JsonSchemaGenerator(using ModelCatalogue.cardinalityExplicit.model)
            .serialize(options),
        )

        def accepts(json: String): Boolean =
          schema.validate(json, InputFormat.JSON).isEmpty

        accepts("""{}""") shouldBe true
        accepts("""{"exactly_two": null}""") shouldBe includeNull
        accepts("""{"exactly_two": ["a", "b"]}""") shouldBe true

        accepts("""{"exactly_two": []}""") shouldBe false
        accepts("""{"exactly_two": ["a"]}""") shouldBe false
        accepts("""{"exactly_two": ["a", "b", "c"]}""") shouldBe false
        accepts("""{"exactly_two": ["a", null]}""") shouldBe false

        accepts("""{"one_to_three": []}""") shouldBe false
        accepts("""{"one_to_three": ["a"]}""") shouldBe true
        accepts("""{"one_to_three": ["a", "b", "c"]}""") shouldBe true
        accepts("""{"one_to_three": ["a", "b", "c", "d"]}""") shouldBe false
      }

    for includeNull <- Seq(false, true) do
      s"LNK-34 enum constraints with includeNull=$includeNull" in {
        val options = JsonSchemaGenerator.Options(includeNull = includeNull)
        val schema = sr.getSchema(
          JsonSchemaGenerator(using ModelCatalogue.`enum`.model).serialize(options),
        )

        def accepts(json: String): Boolean =
          schema.validate(json, InputFormat.JSON).isEmpty

        accepts("""{}""") shouldBe true
        accepts("""{"some_slot": "SOME_OPTION"}""") shouldBe true
        accepts("""{"some_slot": "SOME_OTHER_OPTION"}""") shouldBe true
        accepts("""{"some_slot": null}""") shouldBe includeNull

        accepts("""{"some_slot": "UNDECLARED_OPTION"}""") shouldBe false
        accepts("""{"some_slot": 42}""") shouldBe false
      }

    for includeNull <- Seq(false, true) do
      s"LNK-34 inherited dictionary requirements with includeNull=$includeNull" in {
        val schemaYaml =
          """id: https://example.org/lnk34/inherited-required
            |name: inherited_required
            |imports:
            |  - linkml:types
            |
            |classes:
            |  BaseEntry:
            |    attributes:
            |      id:
            |        range: string
            |        key: true
            |      label:
            |        range: string
            |        required: true
            |
            |  Entry:
            |    is_a: BaseEntry
            |    tree_root: true
            |""".stripMargin

        val model = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(schemaYaml),
        )

        val options = JsonSchemaGenerator.Options(
          treeRootInlineType = Some("compact_dict"),
          includeNull = includeNull,
        )

        val schema = sr.getSchema(
          JsonSchemaGenerator(using model).serialize(options),
        )

        def accepts(json: String): Boolean =
          schema.validate(json, InputFormat.JSON).isEmpty

        accepts("""{}""") shouldBe true
        accepts("""{"YES": {"label": "description"}}""") shouldBe true

        accepts("""{"YES": null}""") shouldBe false
        accepts("""{"YES": {}}""") shouldBe false
        accepts("""{"YES": {"label": null}}""") shouldBe false
        accepts("""null""") shouldBe false
      }

    for entry <- ModelCatalogue.all do
      s"generate Json Schema for model '${entry.model.root.name}'" when {

        lazy val jsonSchema = JsonSchemaGenerator(using entry.model).serialize()

        for valid <- entry.validInstances.filter(_.json.isDefined).distinct do
          s"valid instance '${valid.name}'" in {
            processSkip(entry, valid)
            sr.getSchema(jsonSchema).validate(valid.json.get, InputFormat.JSON) shouldBe empty
          }
        for invalid <- entry.invalidInstances.filter(_.json.isDefined).distinct do
          s"invalid data '${invalid.name}'" in {
            processSkip(entry, invalid)
            sr.getSchema(jsonSchema).validate(
              invalid.json.get,
              InputFormat.JSON,
              (ec: ExecutionContext) =>
                ec.setExecutionConfig(
                  ExecutionConfig.builder().formatAssertionsEnabled(true).build(),
                ),
            ) should not be empty
          }
      }
  }
}

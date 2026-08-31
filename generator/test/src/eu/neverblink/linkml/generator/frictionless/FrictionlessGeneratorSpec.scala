package eu.neverblink.linkml.generator.frictionless

import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import com.github.plokhotnyuk.jsoniter_scala.core.*

class FrictionlessGeneratorSpec extends AnyWordSpec, Matchers {
  val xsd = "http://www.w3.org/2001/XMLSchema#"

  /** The table schema of the model's tree root, which is what most of these tests are about. */
  private def rootTable(sv: SchemaView, treeRoot: Option[String] = None): TableDescriptor = {
    val root = sv.treeRootWithOverride(treeRoot).get
      .getOrElse(fail("the model has no tree root"))
    FrictionlessGenerator(using sv).tableSchema(root)(using FrictionlessGenerator.Options())
  }

  private def load(yaml: String): SchemaView =
    SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))

  "TableDescriptor" should {
    "serialize" when {
      "empty" in {
        writeToString(TableDescriptor()) shouldBe """{"fields":[]}"""
      }
      "field" in {
        writeToString(TableDescriptor(Seq(FieldDescriptor("field")))) shouldBe
          """{"fields":[{"name":"field","type":"string","format":"default"}]}"""
      }
      "required field" in {
        val expected =
          """{"fields":[{"name":"field","type":"string","constraints":{"required":true},"format":"default"}]}"""
        writeToString(
          TableDescriptor(
            Seq(FieldDescriptor("field", constraints = Some(Constraints(required = Some(true))))),
          ),
        ) shouldBe expected
      }
      "foreign key" in {
        val expected =
          """{"fields":[],"foreignKeys":[{"fields":"a","reference":{"resource":"other","fields":"id"}}]}"""
        writeToString(
          TableDescriptor(foreignKeys =
            Some(Seq(ForeignKey("a", ForeignKeyReference("other", "id")))),
          ),
        ) shouldBe expected
      }
    }
  }

  "FrictionlessGenerator" should {
    "generate basic fields" in {
      val td = rootTable(ModelCatalogue.basic.model)
      td.fields should not be empty
      td.fields.map(_.name) should contain theSameElementsAs Seq(
        "some_slot",
        "some_other_slot",
      )
      td.fields.map(_.`type`) should contain theSameElementsAs Seq(
        "string",
        "integer",
      )
      td.fields.flatMap(_.constraints.flatMap(_.required)) should contain theSameElementsAs Seq(
        true,
        false,
      )
    }

    "generate references" in {
      val td = rootTable(ModelCatalogue.reference.model)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "string"
      someSlot.rdfType shouldBe Some(ModelCatalogue.reference.id + "SomeOtherClass")
    }

    "generate types" in {
      val td = rootTable(ModelCatalogue.typed.model)
      val fieldMap = td.fields.map(fd => fd.name -> fd).toMap

      fieldMap("stringSlot").`type` shouldBe "string"
      fieldMap("stringSlot").rdfType shouldBe Some(xsd + "string")

      fieldMap("booleanSlot").`type` shouldBe "boolean"
      fieldMap("booleanSlot").rdfType shouldBe Some(xsd + "boolean")

      fieldMap("intSlot").`type` shouldBe "integer"
      fieldMap("intSlot").rdfType shouldBe Some(xsd + "integer")

      fieldMap("floatSlot").`type` shouldBe "number"
      fieldMap("floatSlot").rdfType shouldBe Some(xsd + "decimal")

      fieldMap("dateSlot").`type` shouldBe "date"
      fieldMap("dateSlot").rdfType shouldBe Some(xsd + "date")

      fieldMap("customSlot").`type` shouldBe "string"
      fieldMap("customSlot").rdfType shouldBe Some(ModelCatalogue.typed.id + "Custom")
    }

    "generate uri format" in {
      val td = rootTable(ModelCatalogue.uri.model)
      val fieldMap = td.fields.map(fd => fd.name -> fd).toMap
      fieldMap("some_slot").`type` shouldBe "string"
      fieldMap("some_slot").format shouldBe "uri"
      fieldMap("some_slot").rdfType shouldBe Some(xsd + "anyURI")
      // reference
      fieldMap("some_other_slot").`type` shouldBe "string"
      fieldMap("some_other_slot").format shouldBe "uri"
      fieldMap("some_other_slot").rdfType shouldBe Some(ModelCatalogue.uri.id + "SomeOtherClass")
    }

    "generate inlines" in {
      val td = rootTable(ModelCatalogue.inlines.explicitInline.model)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "object"
      someSlot.rdfType shouldBe Some(ModelCatalogue.inlines.explicitInline.id + "SomeOtherClass")
    }

    "generate array inlines" in {
      val td = rootTable(ModelCatalogue.inlines.explicitInlineList.model)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "array"
      someSlot.rdfType shouldBe Some(
        ModelCatalogue.inlines.explicitInlineList.id + "SomeOtherClass",
      )
    }

    "generate any" in {
      val td = rootTable(ModelCatalogue.anything.model)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "any"
      // The `any` type accepts any format, so there is nothing to name.
      someSlot.format shouldBe "default"
    }

    "generate any for unknown types" in {
      val td = rootTable(ModelCatalogue.externalType.model)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "any"
    }

    "generate enum values" in {
      val td = rootTable(ModelCatalogue.`enum`.model)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "string"
      someSlot.constraints.get.`enum`.get should contain theSameElementsAs Seq(
        "SOME_OPTION",
        "SOME_OTHER_OPTION",
        "YET_ANOTHER_OPTION",
      )
    }

    "generate type constraints" in {
      val td = rootTable(ModelCatalogue.constraints.model)

      val fieldMap = td.fields.map(fd => fd.name -> fd).toMap
      val intConstraints = fieldMap("intSlot").constraints.get
      intConstraints.minimum shouldBe Some("-1")
      intConstraints.maximum shouldBe Some("1")
      val floatConstraints = fieldMap("floatSlot").constraints.get
      floatConstraints.minimum shouldBe Some("-2.0")
      floatConstraints.maximum shouldBe Some("2.0")

      val stringConstraints = fieldMap("stringSlot").constraints.get
      stringConstraints.pattern shouldBe Some("^([0-9]{3})?[0-9]{3}-[0-9]{4}$")
    }

    "date, time and datetime columns use the ISO 8601 format" in {
      // The `any` format would also accept things like 01/02/2020, which the LinkML types do not.
      val td = rootTable(
        load(
          """id: https://neverblink.eu/test/
          |name: test
          |default_range: string
          |prefixes:
          |  linkml: https://w3id.org/linkml/
          |imports:
          |  - linkml:types
          |classes:
          |  Root:
          |    tree_root: true
          |    attributes:
          |      d:
          |        range: date
          |      t:
          |        range: time
          |      dt:
          |        range: datetime
          |""".stripMargin,
        ),
      )
      val fieldMap = td.fields.map(fd => fd.name -> fd).toMap
      fieldMap("d").`type` shouldBe "date"
      fieldMap("t").`type` shouldBe "time"
      fieldMap("dt").`type` shouldBe "datetime"
      td.fields.map(_.format).distinct shouldBe Seq("default")
    }

    "order columns by rank, then by name" in {
      val td = rootTable(
        load(
          """id: https://neverblink.eu/test/
          |name: test
          |default_range: string
          |types:
          |  string:
          |classes:
          |  Root:
          |    tree_root: true
          |    attributes:
          |      zulu:
          |        rank: 1
          |      alpha:
          |        rank: 2
          |      unranked_b:
          |      unranked_a:
          |""".stripMargin,
        ),
      )
      // Ranked slots come first, in rank order. The rest follow, by name.
      td.fields.map(_.name) shouldBe Seq("zulu", "alpha", "unranked_a", "unranked_b")
    }

    "allow tree root overriding" in {
      val td = rootTable(ModelCatalogue.treeRootless.model, Some("SomeClass"))
      td.fields should not be empty
      td.fields.map(_.name) should contain theSameElementsAs Seq(
        "some_slot",
        "some_other_slot",
      )
      td.fields.map(_.`type`) should contain theSameElementsAs Seq(
        "string",
        "integer",
      )
      td.fields.flatMap(_.constraints.flatMap(_.required)) should contain theSameElementsAs Seq(
        true,
        false,
      )

      val td2 = rootTable(ModelCatalogue.treeRootless.model, Some("SomeOtherClass"))
      td2.fields.length shouldBe 1
      td2.fields.head.name shouldBe "some_slot"
    }

    "generate the model catalogue without throwing errors" when {
      for model <- ModelCatalogue.all do
        s"model '${model.name}'" in {
          val gen = FrictionlessGenerator(using model.model)
          val res = gen.serialize()
          res should not be empty
          res should include("\"resources\"")

          // Both output modes describe the same package, so they agree on the tables.
          val files = gen.generateFiles().toMap
          files.keys should contain("datapackage.json")
          files.keySet.count(_.startsWith("schemas/")) shouldBe
            gen.generate().resources.length
        }
    }
  }

  "a generated data package" should {
    val schema =
      """id: https://neverblink.eu/test/
        |name: dp test
        |default_range: string
        |title: A title
        |description: A description
        |version: "4.5.6"
        |license: CC-BY-4.0
        |keywords: [a, b]
        |types:
        |  string:
        |classes:
        |  Person:
        |    tree_root: true
        |    title: A person
        |    description: Someone with a home and a manager.
        |    attributes:
        |      id:
        |        identifier: true
        |        rank: 1
        |      home:
        |        rank: 2
        |        range: Address
        |      manager:
        |        rank: 3
        |        range: Person
        |      friends:
        |        rank: 4
        |        range: Person
        |        multivalued: true
        |  Address:
        |    attributes:
        |      id:
        |        identifier: true
        |  Note:
        |    attributes:
        |      text:
        |""".stripMargin

    def pkg(
        options: FrictionlessGenerator.Options = FrictionlessGenerator.Options(),
    ): DataPackageDescriptor =
      FrictionlessGenerator(using load(schema)).generate(options)

    "include the schema's metadata" in {
      val p = pkg()
      p.profile shouldBe "tabular-data-package"
      // The schema is named "dp test", which is not a legal package name.
      p.name shouldBe Some("dp-test")
      p.id shouldBe Some("https://neverblink.eu/test/")
      p.title shouldBe Some("A title")
      p.description shouldBe Some("A description")
      p.version shouldBe Some("4.5.6")
      p.keywords shouldBe Some(Seq("a", "b"))
      p.licenses shouldBe Some(Seq(License(name = Some("CC-BY-4.0"))))
    }

    "give every class a tabular resource" in {
      val p = pkg()
      p.resources.map(_.name) shouldBe Seq("address", "note", "person")
      p.resources.map(_.profile).distinct shouldBe Seq("tabular-data-resource")
      p.resources.map(_.path) shouldBe
        Seq("data/address.csv", "data/note.csv", "data/person.csv")
    }

    "carry the class title and description onto its resource" in {
      val person = pkg().resources.find(_.name == "person").get
      person.title shouldBe Some("A person")
      person.description shouldBe Some("Someone with a home and a manager.")
      // Address has neither, and the spec has no place for an empty one.
      val address = pkg().resources.find(_.name == "address").get
      address.title shouldBe None
      address.description shouldBe None
    }

    "keep the class metadata in the split output too" in {
      val files = FrictionlessGenerator(using load(schema)).generateFiles().toMap
      files("datapackage.json") should include("\"title\": \"A person\"")
      files("datapackage.json") should include(
        "\"description\": \"Someone with a home and a manager.\"",
      )
    }

    "point foreign keys at the other tables, and at itself by empty name" in {
      val person = pkg().resources.find(_.name == "person").get.schema match {
        case SchemaRef.Inline(table) => table
        case other => fail(s"expected an inline schema, got $other")
      }
      person.primaryKey shouldBe Some("id")
      person.foreignKeys.get should contain theSameElementsAs Seq(
        ForeignKey("home", ForeignKeyReference("address", "id")),
        // A self-reference is spelled with the empty string, not the table's own name.
        ForeignKey("manager", ForeignKeyReference("", "id")),
      )
      // `friends` is multivalued, so the cell holds a list and there is nothing to key on.
      person.fields.find(_.name == "friends").get.`type` shouldBe "array"
    }

    "drop classes without an identifier on request" in {
      // Note has no identifier; Person and Address both do.
      pkg().resources.map(_.name) should contain("note")
      val skipped = pkg(FrictionlessGenerator.Options(skipClassesWithoutIdentifier = true))
      skipped.resources.map(_.name) shouldBe Seq("address", "person")
    }

    "keep the tree root even when it has no identifier" in {
      val rootless =
        """id: https://neverblink.eu/test/
          |name: test
          |default_range: string
          |types:
          |  string:
          |classes:
          |  Root:
          |    tree_root: true
          |    attributes:
          |      x:
          |""".stripMargin
      FrictionlessGenerator(using load(rootless))
        .generate(FrictionlessGenerator.Options(skipClassesWithoutIdentifier = true))
        .resources.map(_.name) shouldBe Seq("root")
    }

    "inline the schemas in one mode and point at files in the other" in {
      val gen = FrictionlessGenerator(using load(schema))
      val inline = gen.generate()
      val files = gen.generateFiles().toMap

      files.keys should contain theSameElementsAs Seq(
        "datapackage.json",
        "schemas/address.json",
        "schemas/note.json",
        "schemas/person.json",
      )
      // Every schema the one-document mode embeds is byte for byte the file the split mode
      // writes, and the descriptor points at exactly those files.
      inline.resources.foreach { r =>
        files(s"schemas/${r.name}.json") shouldBe
          writeToString(inlineOf(r), WriterConfig.withIndentionStep(2))
        files("datapackage.json") should include(s"\"schema\": \"schemas/${r.name}.json\"")
      }
    }

    "give colliding class names distinct resource names" in {
      val colliding =
        """id: https://neverblink.eu/test/
          |name: test
          |default_range: string
          |types:
          |  string:
          |classes:
          |  First:
          |    alias: A B
          |    tree_root: true
          |    attributes:
          |      x:
          |  Second:
          |    alias: A-B
          |    attributes:
          |      y:
          |""".stripMargin
      val names = FrictionlessGenerator(using load(colliding)).generate().resources.map(_.name)
      names shouldBe Seq("a-b", "a-b-2")
    }

    "refuse to build a package with no tables" in {
      val empty =
        """id: https://neverblink.eu/test/
          |name: test
          |default_range: string
          |types:
          |  string:
          |classes:
          |  Mixin:
          |    mixin: true
          |    attributes:
          |      x:
          |""".stripMargin
      val ex = intercept[RuntimeException] {
        FrictionlessGenerator(using load(empty)).generate()
      }
      ex.getMessage should include("at least one resource")
    }
  }

  private def inlineOf(r: ResourceDescriptor): TableDescriptor = r.schema match {
    case SchemaRef.Inline(table) => table
    case other => fail(s"expected an inline schema, got $other")
  }
}

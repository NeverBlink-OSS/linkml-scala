package eu.neverblink.linkml.generator.tableschema

import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import com.github.plokhotnyuk.jsoniter_scala.core.*
import eu.neverblink.linkml.generator.tableschema.TableSchemaGenerator.*

class TableSchemaGeneratorSpec extends AnyWordSpec, Matchers {
  val xsd = "http://www.w3.org/2001/XMLSchema#"

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
    }
  }

  "TableSchemaGenerator" should {
    "generate basic fields" in {
      val td = TableSchemaGenerator(using ModelCatalogue.basic.model).generate(None)
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
      val td = TableSchemaGenerator(using ModelCatalogue.reference.model).generate(None)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "string"
      someSlot.rdfType shouldBe Some(ModelCatalogue.reference.id + "SomeOtherClass")
    }

    "generate types" in {
      val td = TableSchemaGenerator(using ModelCatalogue.typed.model).generate(None)
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
      val td = TableSchemaGenerator(using ModelCatalogue.uri.model).generate(None)
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
      val td =
        TableSchemaGenerator(using ModelCatalogue.inlines.explicitInline.model).generate(None)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "object"
      someSlot.rdfType shouldBe Some(ModelCatalogue.inlines.explicitInline.id + "SomeOtherClass")
    }

    "generate array inlines" in {
      val td =
        TableSchemaGenerator(using ModelCatalogue.inlines.explicitInlineList.model).generate(None)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "array"
      someSlot.rdfType shouldBe Some(
        ModelCatalogue.inlines.explicitInlineList.id + "SomeOtherClass",
      )
    }

    "generate any" in {
      val td = TableSchemaGenerator(using ModelCatalogue.anything.model).generate(None)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "any"
    }

    "generate any for unknown types" in {
      val td = TableSchemaGenerator(using ModelCatalogue.externalType.model).generate(None)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "any"
    }

    "generate enum values" in {
      val td = TableSchemaGenerator(using ModelCatalogue.`enum`.model).generate(None)
      val someSlot = td.fields.head
      someSlot.`type` shouldBe "string"
      someSlot.constraints.get.`enum`.get should contain theSameElementsAs Seq(
        "SOME_OPTION",
        "SOME_OTHER_OPTION",
        "YET_ANOTHER_OPTION",
      )
    }

    "generate type constraints" in {
      val td = TableSchemaGenerator(using ModelCatalogue.constraints.model).generate(None)

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

    "allow tree root overriding" in {
      val td =
        TableSchemaGenerator(using ModelCatalogue.treeRootless.model).generate(Some("SomeClass"))
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

      val td2 = TableSchemaGenerator(using ModelCatalogue.treeRootless.model).generate(
        Some("SomeOtherClass"),
      )
      td2.fields.length shouldBe 1
      td2.fields.head.name shouldBe "some_slot"
    }

    "generate the model catalogue without throwing errors" when {
      val emptyMaterialized = writeToString(TableDescriptor())
      for model <- ModelCatalogue.all do
        s"model '${model.name}'" in {
          val res = TableSchemaGenerator(using model.model).serialize(None)
          res should not be empty
          res should not be emptyMaterialized
        }
    }
  }
}

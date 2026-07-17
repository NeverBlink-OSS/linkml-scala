package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Success

class SchemaValidatorSpec extends AnyWordSpec, Matchers {
  def load(schemaYaml: String): SchemaView = SchemaView.loadSchemaViewFromString(schemaYaml)

  // Shared part of the schema
  val schemaShared =
    """id: https://neverblink.eu/linkml/referenceValidator/test/
      |name: test
      |"""

  "SchemaValidator" should {
    "find unknown references" in {
      val schemaYaml =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |     - ???
           |     - unknown
           |     - valid
           |slots:
           |  valid:
           |  wrong:
           |    range: nope
           |types:
           |  string:
           |""".stripMargin
      intercept[RuntimeException](load(schemaYaml)).getMessage shouldBe
        """Fatal validation problems:
          |Unknown reference '???' at /classes/SomeClass/slots/0/.
          |Unknown reference 'unknown' at /classes/SomeClass/slots/1/.
          |Unknown reference 'nope' at /slots/wrong/range/.""".stripMargin
    }

    "find unknown references in deeply nested schemas" in {
      val schemaYaml =
        s"""$schemaShared
           |slots:
           |  root:
           |    any_of:
           |     - exactly_one_of:
           |        - none_of:
           |           - all_of:
           |              - range: nope
           |""".stripMargin
      intercept[RuntimeException](load(schemaYaml)).getMessage shouldBe
        """Fatal validation problems:
          |Unknown reference 'nope' at /slots/root/any_of/0/exactly_one_of/0/none_of/0/all_of/0/range/.
          |Undefined range at /slots/root/any_of/0/exactly_one_of/0/none_of/0/range/, schema 'default_range' is undefined, and the fallback 'string' type is not available. Define the 'range' of the slot, add a 'default_range' to the schema, import 'linkml:types', or define a 'string' type to fix.
          |Undefined range at /slots/root/any_of/0/exactly_one_of/0/range/, schema 'default_range' is undefined, and the fallback 'string' type is not available. Define the 'range' of the slot, add a 'default_range' to the schema, import 'linkml:types', or define a 'string' type to fix.
          |Undefined range at /slots/root/any_of/0/range/, schema 'default_range' is undefined, and the fallback 'string' type is not available. Define the 'range' of the slot, add a 'default_range' to the schema, import 'linkml:types', or define a 'string' type to fix.
          |Undefined range at /slots/root/range/, schema 'default_range' is undefined, and the fallback 'string' type is not available. Define the 'range' of the slot, add a 'default_range' to the schema, import 'linkml:types', or define a 'string' type to fix.""".stripMargin
    }

    "format the error" in {
      val schemaYaml =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |     - ???
           |     - unknown
           |     - valid
           |slots:
           |  valid:
           |  wrong:
           |    range: nope
           |types:
           |  string:
           |""".stripMargin
      intercept[RuntimeException](load(schemaYaml)).getMessage shouldBe
        """Fatal validation problems:
          |Unknown reference '???' at /classes/SomeClass/slots/0/.
          |Unknown reference 'unknown' at /classes/SomeClass/slots/1/.
          |Unknown reference 'nope' at /slots/wrong/range/.""".stripMargin
    }

    "fail for non-unique names in the different element kinds: classes, types, and enums" in {
      val schemaYaml =
        s"""$schemaShared
           |prefixes:
           |  linkml: https://w3id.org/linkml/
           |imports:
           |  linkml:types
           |classes:
           |  some1:
           |    slots:
           |     - valid
           |  some2:
           |    slots:
           |     - valid
           |  some3:
           |    slots:
           |     - valid
           |  some4:
           |    slots:
           |     - valid
           |enums:
           |  some2:
           |    permissible_values:
           |      v1:
           |      v2:
           |  some4:
           |    permissible_values:
           |      v1:
           |      v2:
           |  some5:
           |    permissible_values:
           |      v1:
           |      v2:
           |types:
           |  some1:
           |  some4:
           |  some5:
           |slots:
           |  valid:
           |    range: string
           |""".stripMargin

      val sv = load(schemaYaml)

      val msg = SchemaValidator(using sv).validate().failed.get.getMessage

      Seq(
        """Schema validation failed:
          |Non-unique name 'some4' used for enum from 'test' schema and type from 'test' schema
          |Non-unique name 'some5' used for enum from 'test' schema and type from 'test' schema
          |Non-unique name 'some1' used for class from 'test' schema and type from 'test' schema
          |Non-unique name 'some2' used for class from 'test' schema and enum from 'test' schema
          |Non-unique name 'some4' used for class from 'test' schema, enum from 'test' schema, and type from 'test' schema""".stripMargin,
      ) foreach { part =>
        msg should include(part)
      }
    }

    "fail for non-unique names in the same element kinds" in {
      val schemaYaml =
        s"""$schemaShared
           |prefixes:
           |  linkml: https://w3id.org/linkml/
           |imports:
           |  linkml:meta
           |types:
           |  string:
           |enums:
           |  pv_formula_options:
           |    permissible_values:
           |      v1:
           |      v2:
           |classes:
           |  UnitOfMeasure:
           |slots:
           |  name:
           |subsets:
           |  MinimalSubset:
           |""".stripMargin

      val sv = load(schemaYaml)

      val msg = SchemaValidator(using sv).validate().failed.get.getMessage

      Seq(
        """Schema validation failed:
          |Non-unique name 'pv_formula_options' used for enum from 'test' and 'meta' schemas
          |Non-unique name 'string' used for type from 'test' and 'types' schemas
          |Non-unique name 'UnitOfMeasure' used for class from 'units' and 'test' schemas
          |Non-unique name 'name' used for slot from 'meta' and 'test' schemas
          |Non-unique name 'MinimalSubset' used for subset from 'meta' and 'test' schemas""".stripMargin,
      ) foreach { part =>
        msg should include(part)
      }
    }

    "fail on ranges referencing slots" in {
      val schemaYaml =
        s"""$schemaShared
           |slots:
           |  wrong:
           |    range: wrong
           |""".stripMargin
      intercept[RuntimeException](load(schemaYaml)).getMessage shouldBe
        """Fatal validation problems:
          |Invalid range 'wrong' at /slots/wrong/range/, which refers to SlotDefinition. Ranges can only reference types, classes or enums.""".stripMargin
    }

    "fail on ranges referencing subsets" in {
      val schemaYaml =
        s"""$schemaShared
           |subsets:
           |  some_subset:
           |slots:
           |  wrong:
           |    range: some_subset
           |""".stripMargin
      intercept[RuntimeException](load(schemaYaml)).getMessage shouldBe
        """Fatal validation problems:
          |Invalid range 'some_subset' at /slots/wrong/range/, which refers to SubsetDefinition. Ranges can only reference types, classes or enums.""".stripMargin
    }

    "find default_range usages without a valid default_range" in {
      val schemaYaml =
        s"""$schemaShared
           |slots:
           |  wrong:
           |""".stripMargin
      intercept[RuntimeException](load(schemaYaml)).getMessage shouldBe
        """Fatal validation problems:
          |Undefined range at /slots/wrong/range/, schema 'default_range' is undefined, and the fallback 'string' type is not available. Define the 'range' of the slot, add a 'default_range' to the schema, import 'linkml:types', or define a 'string' type to fix.""".stripMargin
    }

    "fail on multiple tree roots" in {
      val schemaYaml =
        s"""$schemaShared
           |classes:
           |  One:
           |    tree_root: true
           |  Two:
           |    tree_root: true
           |""".stripMargin
      val sv = load(schemaYaml)

      val msg = SchemaValidator(using sv).validate().failed.get.getMessage.toLowerCase

      Seq(
        "'one'",
        "'two'",
        "tree_root",
      ) foreach { part =>
        msg should include(part)
      }
    }

    "allow multiple tree roots in imported schemas" in {
      val schemaMain =
        s"""$schemaShared
           |classes:
           |  main_class:
           |    tree_root: true
           |""".stripMargin
      val schemaImported =
        """id: urn:imported
           |name: imported
           |classes:
           |  imported_class:
           |    tree_root: true
           |""".stripMargin

      val sv = SchemaView(
        Seq(
          FileSystemImporter.parseSchema(schemaMain),
          FileSystemImporter.parseSchema(schemaImported),
        ),
      )
      SchemaValidator(using sv).validate() shouldBe a[Success[?]]
    }

    "fail on multiple identifiers" in {
      val schemaYaml =
        s"""$schemaShared
           |classes:
           |  some_class:
           |    slots:
           |    - id1
           |    - id2
           |slots:
           |  id1:
           |    identifier: true
           |  id2:
           |    identifier: true
           |types:
           |  string:
           |""".stripMargin
      val sv = load(schemaYaml)

      val msg = SchemaValidator(using sv).validate().failed.get.getMessage.toLowerCase

      Seq(
        "identifier",
        "key",
        "'id1'",
        "'id2'",
        "some_class",
      ) foreach { part =>
        msg should include(part)
      }
    }

    "fail on multiple keys" in {
      val schemaYaml =
        s"""$schemaShared
           |classes:
           |  some_class:
           |    slots:
           |    - id1
           |    - id2
           |slots:
           |  id1:
           |    key: true
           |  id2:
           |    key: true
           |types:
           |  string:
           |""".stripMargin
      val sv = load(schemaYaml)

      val msg = SchemaValidator(using sv).validate().failed.get.getMessage.toLowerCase

      Seq(
        "identifier",
        "key",
        "'id1'",
        "'id2'",
        "some_class",
      ) foreach { part =>
        msg should include(part)
      }
    }

    "fail on different key / identifier" in {
      val schemaYaml =
        s"""$schemaShared
           |classes:
           |  some_class:
           |    slots:
           |    - id1
           |    - id2
           |slots:
           |  id1:
           |    identifier: true
           |  id2:
           |    key: true
           |types:
           |  string:
           |""".stripMargin
      val sv = load(schemaYaml)

      val msg = SchemaValidator(using sv).validate().failed.get.getMessage.toLowerCase

      Seq(
        "identifier",
        "key",
        "'id1'",
        "'id2'",
        "some_class",
      ) foreach { part =>
        msg should include(part)
      }
    }

    "fail on key / identifier with invalid types" in {
      val schemaYaml =
        s"""$schemaShared
           |prefixes:
           |  linkml: https://w3id.org/linkml/
           |imports:
           |  linkml:meta
           |classes:
           |  some_class:
           |    slots:
           |    - id1
           |  some_another_class:
           |    slots:
           |    - id2
           |slots:
           |  id1:
           |    identifier: true
           |    range: UnitOfMeasure
           |  id2:
           |    key: true
           |    range: pv_formula_options
           |""".stripMargin
      val sv = load(schemaYaml)

      val msg = SchemaValidator(using sv).validate().failed.get.getMessage

      Seq(
        """Schema validation failed:
          |Invalid type of key / identifier slot in class 'some_another_class': 'pv_formula_options'. Expected a basic, scalar data type (e.g., string, integer, float, uri).
          |Invalid type of key / identifier slot in class 'some_class': 'UnitOfMeasure'. Expected a basic, scalar data type (e.g., string, integer, float, uri).""".stripMargin,
      ) foreach { part =>
        msg should include(part)
      }
    }

    "provide a linkml:types hint for invalid string ranges" in {
      val schemaYaml =
        s"""$schemaShared
           |slots:
           |  wrong:
           |    range: string
           |""".stripMargin
      intercept[RuntimeException](load(schemaYaml)).getMessage shouldBe
        """Fatal validation problems:
          |Unknown reference 'string' at /slots/wrong/range/. Make sure you have 'linkml:types' imported.""".stripMargin
    }

    "explain undefined ranges" in {
      val schemaYaml =
        s"""$schemaShared
           |slots:
           |  wrong:
           |""".stripMargin
      intercept[RuntimeException](load(schemaYaml)).getMessage shouldBe
        """Fatal validation problems:
          |Undefined range at /slots/wrong/range/, schema 'default_range' is undefined, and the fallback 'string' type is not available. Define the 'range' of the slot, add a 'default_range' to the schema, import 'linkml:types', or define a 'string' type to fix.""".stripMargin
    }

    "explain invalid ranges" in {
      val schemaYaml =
        s"""$schemaShared
           |slots:
           |  wrong:
           |    range: wrong
           |""".stripMargin
      intercept[RuntimeException](load(schemaYaml)).getMessage shouldBe
        """Fatal validation problems:
          |Invalid range 'wrong' at /slots/wrong/range/, which refers to SlotDefinition. Ranges can only reference types, classes or enums.""".stripMargin
    }

    "warn on no tree root" in {
      val schemaYaml =
        s"""$schemaShared
           |""".stripMargin
      val sv = load(schemaYaml)

      val msg = SchemaValidator(using sv).lint().get

      msg should include("tree_root")
    }

    "warn on no default_range and no linkml:types" in {
      val schemaYaml =
        s"""$schemaShared
           |""".stripMargin
      val sv = load(schemaYaml)

      val msg = SchemaValidator(using sv).lint().get

      msg should include("default_range")
    }

    "explain default_range" in {
      val schemaYaml =
        s"""$schemaShared
           |""".stripMargin
      val sv = load(schemaYaml)

      val msg = SchemaValidator(using sv).lint(verbose = true).get

      Seq(
        "'default_range'",
        "'string'",
        "fallback",
        "will become a fatal error",
        "'range'",
        "'linkml:types'",
        "'string'",
      ) foreach { part =>
        msg should include(part)
      }
    }

    "validate the metamodel" in {
      val sv = SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/meta")
      SchemaValidator(using sv).validate() shouldBe a[Success[?]]
    }
  }
}

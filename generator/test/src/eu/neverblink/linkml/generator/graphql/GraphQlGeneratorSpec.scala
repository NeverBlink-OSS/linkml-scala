package eu.neverblink.linkml.generator.graphql

import eu.neverblink.linkml.generator.util.PruningMode
import eu.neverblink.linkml.generator.util.PruningMode.skip
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GraphQlGeneratorSpec extends AnyWordSpec, Matchers {
  "GraphQlGenerator" should {
    "generate types for classes" in {
      given SchemaView = ModelCatalogue.basic2.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "type SomeClass",
        "some_slot: String",
        "type SomeOtherClass",
        "some_other_slot: Int!",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate class references" in {
      given SchemaView = ModelCatalogue.reference.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "some_slot: SomeOtherClass\n",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate multivalued references" in {
      given SchemaView = ModelCatalogue.multivaluedReference.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "some_slot: [SomeOtherClass]!\n",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate inlines as references" in {
      given SchemaView = ModelCatalogue.inlines.explicitInline.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "some_slot: SomeOtherClass\n",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate multivalued inlines as references" in {
      given SchemaView = ModelCatalogue.inlines.explicitInlineList.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "some_slot: [SomeOtherClass]!\n",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate cardinality" in {
      given SchemaView = ModelCatalogue.cardinality.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "one: String!\n",
        "at_most_one: String\n",
        "at_least_one: [String]!\n",
        "zero_or_more: [String]!\n",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "map to builtin scalars" in {
      given SchemaView = ModelCatalogue.typed.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "string_slot: String!",
        "int_slot: Int!",
        "boolean_slot: Boolean!",
        "custom_slot: String!", // has string base
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate scalars for URIs" in {
      given SchemaView = ModelCatalogue.uri.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "scalar Uri",
        "some_slot: Uri",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate scalars for custom types" in {
      given SchemaView = ModelCatalogue.externalType.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "scalar ExtType",
        "some_slot: ExtType",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate enums" in {
      given SchemaView = ModelCatalogue.`enum`.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "some_slot: SomeEnum",
        "enum SomeEnum",
        "SOME_OPTION",
        "SOME_OTHER_OPTION",
        "YET_ANOTHER_OPTION",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate Any as a scalar" in {
      given SchemaView = ModelCatalogue.anything.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "some_slot: Any",
        "scalar Any",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate descriptions" in {
      given SchemaView = ModelCatalogue.metadata.title.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "This is a class for testing purposes",
        "This is a slot for testing purposes",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate empty classes" in {
      given SchemaView = ModelCatalogue.emptyClass.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "SomeClass",
        "SomeOtherClass",
        "_: String",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate split interface/implementation when concrete inheritance" in {
      given SchemaView = ModelCatalogue.inheritance.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "interface BaseClassInterface",
        "type BaseClass implements BaseClassInterface",
        "type ChildClass implements BaseClassInterface",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate interface ranges when concrete inheritance" in {
      given SchemaView = ModelCatalogue.inheritance.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "interface BaseRefClassInterface",
        "type BaseRefClass implements BaseRefClassInterface",
        "yet_another_slot: BaseRefClassInterface!",
        "type RefClass implements BaseRefClassInterface",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "prune unused linkml:types elements by default" in {
      given SchemaView = ModelCatalogue.reference.model

      val result = GraphQlGenerator().serialize()
      Seq(
        "scalar uri",
        "scalar uriorcurie",
        "scalar nodeidentifier",
      ).foreach { snippet =>
        result should not include snippet
      }
    }

    "include unused linkml:types elements if requested" in {
      given SchemaView = ModelCatalogue.reference.model

      val result = GraphQlGenerator().serialize(GraphQlGenerator.Options(skip))
      Seq(
        "scalar Uri",
        "scalar Uriorcurie",
        "scalar Nodeidentifier",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "prune in tree_root mode if requested" in {
      given SchemaView = ModelCatalogue.pruning.model

      val result =
        GraphQlGenerator().serialize(GraphQlGenerator.Options(PruningMode.treeRoot(None)))
      Seq(
        "type SomeClass",
        "interface SomeOtherClass",
      ).foreach { snippet =>
        result should include(snippet)
      }
      Seq(
        "scalar uri",
        "scalar uriorcurie",
        "scalar nodeidentifier",
        "NonTreeRootClass",
      ).foreach { snippet =>
        result should not include (snippet)
      }
    }

    "prune in tree_root mode with override" in {
      given SchemaView = ModelCatalogue.pruning.model

      val result =
        GraphQlGenerator().serialize(
          GraphQlGenerator.Options(PruningMode.treeRoot(Some("NotTreeRootClass"))),
        )
      Seq(
        "type NotTreeRootClass",
      ).foreach { snippet =>
        result should include(snippet)
      }
      Seq(
        "scalar uri",
        "scalar uriorcurie",
        "scalar nodeidentifier",
        "type SomeClass",
        "interface SomeOtherClass",
      ).foreach { snippet =>
        result should not include (snippet)
      }
    }

    "handle leading digits in names" in {
      given SchemaView = SchemaView.loadSchemaViewFromString(
        """id: urn:digit
          |name: digit
          |
          |types:
          |  1t:
          |
          |classes:
          |  1C:
          |    slots:
          |     - 1s
          |     - 2s
          |slots:
          |  1s:
          |    range: 1t
          |  2s:
          |    range: 1E
          |enums:
          |  1E:
          |    permissible_values:
          |      1V:
          |      2V:
          |""".stripMargin,
      ).getOrElse(fail("bad schema"))

      val result =
        GraphQlGenerator().serialize()
      Seq(
        "_1T",
        "_1C",
        "_1_s",
        "_2_s",
        "_1E",
        "_1_V",
        "_2_V",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "not throw on empty names" in {
      // the output is likely invalid, but it's reported as a LinkML error earlier
      given SchemaView = SchemaView.loadSchemaViewFromString(
        """id: urn:digit
          |name: digit
          |
          |classes:
          |  '%':
          |""".stripMargin,
      ).getOrElse(fail("bad schema"))

      val result =
        GraphQlGenerator().serialize()
      Seq(
        "type _  {",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }
  }
}

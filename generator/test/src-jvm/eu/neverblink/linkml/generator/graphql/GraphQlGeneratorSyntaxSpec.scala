package eu.neverblink.linkml.generator.graphql

import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import eu.neverblink.linkml.schemaview.SchemaView
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import sangria.schema.Schema
import sangria.parser.{ParserConfig, QueryParser}

class GraphQlGeneratorSyntaxSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  override val skipModels: Map[String, String] = Map(
    "syntheticUris" -> "Escaping not implemented",
    "typeDesignator" -> "Non-abstract inheritance not allowed",
    "unionRangeReference" -> "Non-abstract inheritance not allowed",
//    "inheritance" -> "Non-abstract inheritance not allowed",
    "implicitInlineAsList" -> "LNK-???: Empty classes don't work in graphql gen",
  )

  def parseOrThrow(schema: String): Schema[Any, Any] = {
    val doc = QueryParser.parse(schema, ParserConfig())
    Schema.buildFromAst(doc.get)
  }

  "GraphQlGenerator" should {
    for entry <- ModelCatalogue.all do
      s"generate a valid schema for model '${entry.name}'" in {
        processSkip(entry.name, "")
        val schema = {
          """# dummy query object to make the generated types a valid graphql schema
            |type Query {
            |  test: String
            |}
            |""".stripMargin +
            GraphQlGenerator(using entry.model).serialize()
        }
        val result = parseOrThrow(schema)
        result.directives
          .map(_.name) should contain allOf ("linkml_uri", "linkml_identifier")

        result.typeList should not be empty
      }

    "generate the metamodel" in {
      val schema = {
        """# dummy query object to make the generated types a valid graphql schema
          |type Query {
          |  test: String
          |}
          |""".stripMargin +
          GraphQlGenerator(using SchemaView.loadSchemaViewFromUri("linkml:meta")).serialize()
      }

      val result = parseOrThrow(schema)
      result.directives
        .map(_.name) should contain allOf("linkml_uri", "linkml_identifier")

      result.typeList should not be empty
    }
  }

  "sanity" in {
    a[sangria.parser.SyntaxError] should be thrownBy {
      parseOrThrow("blep")
    }
  }
}

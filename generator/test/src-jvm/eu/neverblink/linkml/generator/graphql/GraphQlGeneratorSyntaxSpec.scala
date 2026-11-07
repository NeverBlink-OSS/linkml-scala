package eu.neverblink.linkml.generator.graphql

import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import eu.neverblink.linkml.schemaview.SchemaIssues
import eu.neverblink.linkml.schemaview.SchemaView
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import sangria.ast
import sangria.schema.Schema
import sangria.parser.{ParserConfig, QueryParser}

class GraphQlGeneratorSyntaxSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  // dummy query object to make the generated types a valid graphql schema
  val dummyQuery: String =
    """type Query {
      |  test: String
      |}
      |""".stripMargin

  def parseOrThrow(schema: String): Schema[Any, Any] = {
    val doc = QueryParser.parse(schema, ParserConfig()).get
    checkInterfaceFields(doc)
    Schema.buildFromAst(doc)
  }

  /** Checks that each type and interface has all fields of the interfaces it implements, as GraphQL
    * requires. Sangria adds a missing field from the interface, so it doesn't report it.
    */
  private def checkInterfaceFields(doc: ast.Document): Unit = {
    val interfaceFields = doc.definitions.collect { case i: ast.InterfaceTypeDefinition =>
      i.name -> i.fields.map(_.name).toSet
    }.toMap
    def check(name: String, interfaces: Seq[ast.NamedType], fields: Seq[ast.FieldDefinition]) =
      interfaces.foreach { i =>
        val missing = interfaceFields.getOrElse(i.name, Set.empty) -- fields.map(_.name)
        withClue(s"$name misses fields of ${i.name}: ")(missing shouldBe empty)
      }
    doc.definitions.foreach {
      case o: ast.ObjectTypeDefinition => check(o.name, o.interfaces, o.fields)
      case i: ast.InterfaceTypeDefinition => check(i.name, i.interfaces, i.fields)
      case _ =>
    }
  }

  "GraphQlGenerator" should {
    for entry <- ModelCatalogue.all do
      s"generate a valid schema for model '${entry.name}'" in {
        processSkip(entry.name, "")
        val schema = {
          dummyQuery +
            GraphQlGenerator(using entry.model).serialize()
        }
        val result = parseOrThrow(schema)

        result.typeList.map(_.name) should contain(
          GraphQlRenamer.className(entry.model.treeRoot.get),
        )
      }

    "give an implementation the stub field of an interface whose fields a descendant changes" in {
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromString(
          """id: https://example.org/stub/
            |name: stub
            |prefixes:
            |  linkml: https://w3id.org/linkml/
            |default_prefix: stub
            |imports:
            |  - linkml:types
            |classes:
            |  Base:
            |    slots:
            |      - labels
            |  Child:
            |    tree_root: true
            |    is_a: Base
            |    slot_usage:
            |      labels:
            |        multivalued: true
            |  Empty:
            |    abstract: true
            |  Filled:
            |    is_a: Empty
            |    slots:
            |      - labels
            |slots:
            |  labels:
            |    range: string
            |""".stripMargin,
        ),
      )
      val result = parseOrThrow(dummyQuery + GraphQlGenerator(using sv).serialize())
      result.typeList.map(_.name) should contain allOf ("Base", "Child", "Filled")
    }

    "generate the metamodel" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta"))
      val schema = {
        dummyQuery +
          GraphQlGenerator(using sv).serialize()
      }

      val result = parseOrThrow(schema)

      result.typeList.map(_.name) should contain(GraphQlRenamer.className(sv.treeRoot.get))
    }
  }
}

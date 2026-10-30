package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FunctionViewSpec extends AnyWordSpec, Matchers {
  import FunctionViewSpec.*

  "SchemaView" should {
    "expose the functions of the root schema" in {
      val sv = load(root)
      sv.functions.keys should contain theSameElementsAs Seq("search", "reindex", "count")
      sv.getElement("search") shouldBe Some(sv.functions("search"))
    }

    "expose the functions of imported schemas" in {
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromUri(
          "importer",
          MapImporter("importer.yaml" -> importer, "root.yaml" -> root),
        ),
      )
      sv.functions.keys should contain theSameElementsAs Seq(
        "search",
        "reindex",
        "count",
        "newest",
      )
      sv.functions("newest").definingSchema.name shouldBe "importer"
      sv.functions("search").definingSchema.name shouldBe "root"
    }
  }

  "FunctionView" should {
    val sv = load(root)

    "resolve the parameters, the return value and the errors" in {
      val search = sv.functions("search")
      search.parameters.map(_.name) shouldBe Some("Request")
      search.returns.map(_.name) shouldBe Some("Result")
      search.errors.map(_.name) shouldBe Seq("BadQuery")
    }

    "leave the parameters and the return value empty when there are none" in {
      val reindex = sv.functions("reindex")
      reindex.parameters shouldBe None
      reindex.returns shouldBe None
      reindex.errors shouldBe empty
    }

    "resolve a return value that is a type" in {
      sv.functions("count").returns.map(_.name) shouldBe Some("integer")
    }

    "use the schema's default prefix for its URI" in {
      sv.functions("search").uriStr shouldBe "https://example.org/functions/search"
    }
  }
}

object FunctionViewSpec {
  def load(schemaYaml: String): SchemaView =
    SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(schemaYaml))

  val root: String =
    """id: https://example.org/functions
      |name: root
      |description: A schema with functions.
      |prefixes:
      |  linkml: https://w3id.org/linkml/
      |  ex: https://example.org/functions/
      |default_prefix: ex
      |default_range: string
      |imports:
      |  - linkml:types
      |classes:
      |  Request:
      |    description: What search takes.
      |    attributes:
      |      q:
      |        description: The query.
      |  Result:
      |    description: What search gives back.
      |    attributes:
      |      total:
      |        description: How many matches there are.
      |        range: integer
      |  BadQuery:
      |    description: The query could not be parsed.
      |    attributes:
      |      message:
      |        description: What is wrong with it.
      |functions:
      |  search:
      |    description: Looks things up.
      |    parameters: Request
      |    returns: Result
      |    errors:
      |      - BadQuery
      |  reindex:
      |    description: Rebuilds the index. Takes nothing, gives nothing back.
      |  count:
      |    description: How many things there are.
      |    returns: integer
      |""".stripMargin

  val importer: String =
    """id: https://example.org/importer
      |name: importer
      |description: A schema that imports one with functions.
      |prefixes:
      |  linkml: https://w3id.org/linkml/
      |  ex2: https://example.org/importer/
      |default_prefix: ex2
      |default_range: string
      |imports:
      |  - linkml:types
      |  - root
      |functions:
      |  newest:
      |    description: The most recent result.
      |    returns: Result
      |""".stripMargin
}

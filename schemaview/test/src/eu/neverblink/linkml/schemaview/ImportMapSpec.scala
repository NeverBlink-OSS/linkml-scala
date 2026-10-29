package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.SchemaDefinition
import eu.neverblink.linkml.validation.SchemaImportError
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ImportMapSpec extends AnyWordSpec, Matchers, Inside {

  /** A schema text with the given name, prefixes and imports. */
  private def schemaText(
      name: String,
      prefixes: Map[String, String] = Map.empty,
      imports: Seq[String] = Nil,
      extra: String = "",
  ): String = {
    val sb = new StringBuilder(s"id: https://neverblink.eu/linkml/import-map/$name\nname: $name\n")
    if (prefixes.nonEmpty) {
      sb.append("prefixes:\n")
      prefixes.foreach((p, u) => sb.append(s"  $p: $u\n"))
    }
    if (imports.nonEmpty) {
      sb.append("imports:\n")
      imports.foreach(i => sb.append(s"  - $i\n"))
    }
    sb.append(extra).toString
  }

  private def schema(
      prefixes: Map[String, String] = Map.empty,
      extra: String = "",
  ): SchemaDefinition =
    inside(FileSystemImporter.parseSchema(schemaText("s", prefixes, extra = extra))) {
      case Right(s) => s
    }

  private def importMap(entries: (String, String)*): ImportMap =
    inside(ImportMap(entries.toMap)) { case Right(map) => map }

  private def rejected(entries: (String, String)*): SchemaImportError =
    inside(ImportMap(entries.toMap)) { case Left(issue) => issue }

  private def loadedNames(uri: String, importer: Importer): Seq[String] =
    inside(SchemaView.loadSchemas(uri, importer)) { case Right(schemas) => schemas.map(_.name) }

  private def loadError(uri: String, importer: Importer): SchemaImportError =
    inside(SchemaView.loadSchemas(uri, importer)) { case Left(issue: SchemaImportError) => issue }

  "ImportMap.lookupKey" should {
    "use a full URI as it is" in {
      ImportMap.lookupKey("https://example.org/units/core", schema(), "a/model.yaml") shouldBe
        Right("https://example.org/units/core.yaml")
      ImportMap.lookupKey("urn:example:model/common", schema(), "a/model.yaml") shouldBe
        Right("urn:example:model/common.yaml")
    }

    "expand a CURIE with the prefixes of the importing schema" in {
      val s = schema(prefixes = Map("units" -> "https://example.org/units/"))
      ImportMap.lookupKey("units:core", s, "a/model.yaml") shouldBe
        Right("https://example.org/units/core.yaml")
    }

    "expand a CURIE with the emit_prefixes of the metamodel" in {
      ImportMap.lookupKey("linkml:types", schema(), "a/model.yaml") shouldBe
        Right("https://w3id.org/linkml/types.yaml")
    }

    "expand a CURIE with the semweb_context prefixes when the schema lists them" in {
      val s = schema(extra = "default_curi_maps:\n  - semweb_context\n")
      ImportMap.lookupKey("foaf:core", s, "") shouldBe Right("http://xmlns.com/foaf/0.1/core.yaml")
      inside(ImportMap.lookupKey("foaf:core", schema(), "")) { case Left(_) => }
    }

    "let the schema's own prefixes have precedence over the emit_prefixes" in {
      val s = schema(prefixes = Map("linkml" -> "https://example.org/my-linkml/"))
      ImportMap.lookupKey("linkml:types", s, "") shouldBe
        Right("https://example.org/my-linkml/types.yaml")
    }

    "report an unknown prefix with the prefix, the import and the schema" in {
      inside(ImportMap.lookupKey("base:core", schema(), "a/model.yaml")) { case Left(issue) =>
        issue.importUri shouldBe "base:core"
        issue.reason should include("'base'")
        issue.reason should include("https://neverblink.eu/linkml/import-map/s")
      }
    }

    "join a relative path to the directory of the importing schema" in {
      ImportMap.lookupKey("./core", schema(), "a/model.yaml") shouldBe Right("a/core.yaml")
      ImportMap.lookupKey("./core", schema(), "b/model.yaml") shouldBe Right("b/core.yaml")
      ImportMap.lookupKey("../common/core", schema(), "a/model.yaml") shouldBe
        Right("common/core.yaml")
      ImportMap.lookupKey("core", schema(), "https://example.org/x/model.yaml") shouldBe
        Right("https://example.org/x/core.yaml")
      ImportMap.lookupKey("core", schema(), "model.yaml") shouldBe Right("core.yaml")
    }

    "use an absolute path as it is" in {
      ImportMap.lookupKey("/schemas/core", schema(), "a/model.yaml") shouldBe
        Right("/schemas/core.yaml")
      ImportMap.lookupKey("C:\\schemas\\core", schema(), "a/model.yaml") shouldBe
        Right("C:\\schemas\\core.yaml")
      ImportMap.lookupKey("C:/schemas/core", schema(), "a/model.yaml") shouldBe
        Right("C:/schemas/core.yaml")
    }

    "keep the .yml extension that the import asks for" in {
      ImportMap.lookupKey("core.yml", schema(), "a/model.yaml") shouldBe Right("a/core.yml")
    }
  }

  "ImportMap.rootLookupKey" should {
    "use a full URI as it is" in {
      ImportMap.rootLookupKey("https://example.org/model") shouldBe "https://example.org/model.yaml"
    }

    "not join a relative path to anything" in {
      ImportMap.rootLookupKey("./a/model") shouldBe "a/model.yaml"
      ImportMap.rootLookupKey("C:\\a\\model.yml") shouldBe "C:\\a\\model.yml"
    }
  }

  "ImportMap.apply" should {
    "accept full URIs and paths as keys" in {
      ImportMap(
        Map(
          "https://example.org/units/core" -> "deps/units/core.yaml",
          "https://example.org/sensors/" -> "deps/sensors/",
          "urn:example:model/common" -> "deps/common.yaml",
          "C:\\deps\\units\\" -> "D:/units/",
          "a/core" -> "/schemas/core.yaml",
          "https://w3id.org/linkml/" -> "local-metamodel/",
        ),
      ).isRight shouldBe true
    }

    "reject a CURIE key, whatever its prefix" in {
      val issue = rejected("linkml:types" -> "local/types.yaml")
      issue.importUri shouldBe "linkml:types"
      issue.reason should include("full URI")
      rejected("base:core" -> "deps/core.yaml").importUri shouldBe "base:core"
      rejected("linkml:" -> "local-metamodel/").importUri shouldBe "linkml:"
    }

    "reject a CURIE target" in {
      rejected("https://example.org/core" -> "base:core").importUri shouldBe "base:core"
      rejected("https://example.org/types" -> "linkml:types").importUri shouldBe "linkml:types"
    }

    "reject two keys with the same lookup key and different targets" in {
      val issue = rejected(
        "https://w3id.org/linkml/types" -> "local/types-a.yaml",
        "https://w3id.org/linkml/types.yaml" -> "local/types-b.yaml",
      )
      issue.reason should include("'https://w3id.org/linkml/types'")
      issue.reason should include("'https://w3id.org/linkml/types.yaml'")
      issue.reason should include("local/types-a.yaml")
      issue.reason should include("local/types-b.yaml")
      rejected(
        "https://example.org/core.yaml" -> "a.yaml",
        "https://example.org/core.yml" -> "b.yaml",
      )
      rejected("https://example.org/core" -> "a.yaml", "https://example.org/core.yaml" -> "b.yaml")
    }

    "accept two keys with the same lookup key and the same target" in {
      ImportMap(
        Map(
          "https://w3id.org/linkml/types" -> "local/types.yaml",
          "https://w3id.org/linkml/types.yaml" -> "./local/types",
        ),
      ).isRight shouldBe true
    }
  }

  "ImportMap.fromJson" should {
    "build a map from a JSON object" in {
      val json =
        """{
          |  "https://example.org/units/": "deps/units/",
          |  "https://w3id.org/linkml/types": "local/types.yaml"
          |}""".stripMargin
      inside(ImportMap.fromJson(json)) { case Right(map) =>
        map.resolve("https://example.org/units/core.yaml") shouldBe Right("deps/units/core.yaml")
        map.resolve("https://w3id.org/linkml/types.yaml") shouldBe Right("local/types.yaml")
      }
      inside(ImportMap.fromJson("""{"https://example.org/a":"a.yaml"}""")) { case Right(map) =>
        map.resolve("https://example.org/a.yaml") shouldBe Right("a.yaml")
      }
      inside(ImportMap.fromJson("{}")) { case Right(map) =>
        map.resolve("core.yaml") shouldBe Right("core.yaml")
      }
    }

    "name the source in the errors" in {
      inside(ImportMap.fromJson("[]", "map.json")) { case Left(issue) =>
        issue.importUri shouldBe "map.json"
        issue.reason should include("not a JSON object")
      }
    }

    "reject a location that is not a string" in {
      inside(ImportMap.fromJson("""{"https://example.org/a": 1}""")) { case Left(issue) =>
        issue.reason should include("'https://example.org/a'")
        issue.reason should include("not a string")
      }
    }

    "reject text that is not valid JSON" in {
      inside(ImportMap.fromJson("""{"https://example.org/a": """)) { case Left(issue) =>
        issue.reason should include("not valid JSON")
      }
    }

    "check the entries as ImportMap.apply does" in {
      inside(ImportMap.fromJson("""{"linkml:types": "types.yaml"}""")) { case Left(issue) =>
        issue.reason should include("CURIE")
      }
    }
  }

  "ImportMap.resolve" should {
    "return a lookup key that no entry matches unchanged" in {
      importMap("https://example.org/a" -> "a.yaml").resolve("https://example.org/b.yaml") shouldBe
        Right("https://example.org/b.yaml")
      ImportMap.empty.resolve("core.yaml") shouldBe Right("core.yaml")
    }

    "match an exact key whatever the extension" in {
      val map = importMap("https://example.org/units/core" -> "deps/units/core.yaml")
      map.resolve("https://example.org/units/core.yaml") shouldBe Right("deps/units/core.yaml")
      map.resolve("https://example.org/units/core.yml") shouldBe Right("deps/units/core.yaml")
    }

    "replace the matching start of a lookup key for a prefix key" in {
      val map = importMap("https://example.org/units/" -> "deps/units/")
      map.resolve("https://example.org/units/core.yaml") shouldBe Right("deps/units/core.yaml")
      importMap("https://example.org/" -> "blep/").resolve("https://example.org/blip.yaml") shouldBe
        Right("blep/blip.yaml")
    }

    "use the longest matching prefix key" in {
      val map = importMap(
        "https://example.org/" -> "all/",
        "https://example.org/units/" -> "units/",
      )
      map.resolve("https://example.org/units/core.yaml") shouldBe Right("units/core.yaml")
      map.resolve("https://example.org/other.yaml") shouldBe Right("all/other.yaml")
    }

    "let an exact key have precedence over a prefix key" in {
      val map = importMap(
        "https://example.org/units/" -> "units/",
        "https://example.org/units/core" -> "special/core.yaml",
      )
      map.resolve("https://example.org/units/core.yaml") shouldBe Right("special/core.yaml")
    }

    "map all linkml: imports with a prefix key for the metamodel URI" in {
      importMap("https://w3id.org/linkml/" -> "local-metamodel/").resolve(
        "https://w3id.org/linkml/types.yaml",
      ) shouldBe
        Right("local-metamodel/types.yaml")
    }

    "follow chained mappings" in {
      val map = importMap(
        "https://example.org/a" -> "https://example.org/b",
        "https://example.org/b" -> "./c",
      )
      map.resolve("https://example.org/a.yaml") shouldBe Right("c.yaml")
    }

    "report a chain that leads back to a key already seen" in {
      val map = importMap(
        "https://example.org/a" -> "https://example.org/b",
        "https://example.org/b" -> "https://example.org/a",
      )
      inside(map.resolve("https://example.org/a.yaml")) { case Left(issue) =>
        issue.importUri shouldBe "https://example.org/a.yaml"
        issue.reason should include("https://example.org/b.yaml")
      }
    }

    "report a chain that keeps growing" in {
      val map = importMap("https://example.org/" -> "https://example.org/v2/")
      inside(map.resolve("https://example.org/core.yaml")) { case Left(issue) =>
        issue.reason should include("https://example.org/v2/v2/core.yaml")
      }
    }
  }

  "loading with an import map" should {
    "load a URI import from the mapped location" in {
      val importer = ImportMapImporter(
        importMap("https://example.org/units/schema" -> "deps/units.yaml"),
        MapImporter(
          "model.yaml" -> schemaText("model", imports = Seq("https://example.org/units/schema")),
          "deps/units.yaml" -> schemaText("units"),
        ),
      )
      loadedNames("model.yaml", importer) shouldBe Seq("model", "units")
    }

    "load a CURIE import from the mapped location" in {
      val importer = ImportMapImporter(
        importMap("https://example.org/units/" -> "deps/units/"),
        MapImporter(
          "model.yaml" -> schemaText(
            "model",
            prefixes = Map("units" -> "https://example.org/units/"),
            imports = Seq("units:core"),
          ),
          "deps/units/core.yaml" -> schemaText("units"),
        ),
      )
      loadedNames("model.yaml", importer) shouldBe Seq("model", "units")
    }

    "load a schema once when it is imported as a CURIE and as a URI" in {
      val importer = ImportMapImporter(
        importMap("https://example.org/units/core" -> "deps/units.yaml"),
        MapImporter(
          "model.yaml" -> schemaText(
            "model",
            prefixes = Map("units" -> "https://example.org/units/"),
            imports = Seq(
              "units:core",
              "https://example.org/units/core",
              "https://example.org/units/core.yaml",
            ),
          ),
          "deps/units.yaml" -> schemaText("units"),
        ),
      )
      loadedNames("model.yaml", importer) shouldBe Seq("model", "units")
    }

    "expand a CURIE import with the prefixes of the schema that contains it" in {
      // Both schemas declare `ex`, with different URIs.
      val importer = ImportMapImporter(
        importMap(
          "https://a.org/core" -> "a-core.yaml",
          "https://b.org/units" -> "b-units.yaml",
        ),
        MapImporter(
          "root.yaml" -> schemaText(
            "root",
            prefixes = Map("ex" -> "https://a.org/"),
            imports = Seq("ex:core"),
          ),
          "a-core.yaml" -> schemaText(
            "core",
            prefixes = Map("ex" -> "https://b.org/"),
            imports = Seq("ex:units"),
          ),
          "b-units.yaml" -> schemaText("units"),
        ),
      )
      loadedNames("root.yaml", importer) shouldBe Seq("root", "core", "units")
    }

    "map two schemas that import the same CURIE with different prefixes to different files" in {
      val importer = ImportMapImporter(
        importMap("https://a.org/core" -> "a-core.yaml", "https://b.org/core" -> "b-core.yaml"),
        MapImporter(
          "root.yaml" -> schemaText("root", imports = Seq("a", "b")),
          "a.yaml" -> schemaText(
            "a",
            prefixes = Map("base" -> "https://a.org/"),
            imports = Seq("base:core"),
          ),
          "b.yaml" -> schemaText(
            "b",
            prefixes = Map("base" -> "https://b.org/"),
            imports = Seq("base:core"),
          ),
          "a-core.yaml" -> schemaText("a_core"),
          "b-core.yaml" -> schemaText("b_core"),
        ),
      )
      loadedNames("root.yaml", importer) shouldBe Seq("root", "a", "a_core", "b", "b_core")
    }

    "resolve the relative imports of a mapped schema against its actual location" in {
      val importer = ImportMapImporter(
        importMap("https://example.org/units/schema" -> "deps/units/units.yaml"),
        MapImporter(
          "model.yaml" -> schemaText("model", imports = Seq("https://example.org/units/schema")),
          "deps/units/units.yaml" -> schemaText("units", imports = Seq("./quantities")),
          "deps/units/quantities.yaml" -> schemaText("quantities"),
        ),
      )
      loadedNames("model.yaml", importer) shouldBe Seq("model", "units", "quantities")
    }

    "join a relative import before it is matched" in {
      val importer = ImportMapImporter(
        importMap("a/core" -> "shared/core.yaml"),
        MapImporter(
          "a/model.yaml" -> schemaText("model", imports = Seq("./core")),
          "shared/core.yaml" -> schemaText("core"),
        ),
      )
      loadedNames("a/model.yaml", importer) shouldBe Seq("model", "core")
    }

    "apply the map to the root URI" in {
      val importer = ImportMapImporter(
        importMap("https://example.org/model" -> "local/model.yaml"),
        MapImporter(
          "local/model.yaml" -> schemaText("model", imports = Seq("./core")),
          "local/core.yaml" -> schemaText("core"),
        ),
      )
      loadedNames("https://example.org/model", importer) shouldBe Seq("model", "core")
    }

    "apply the map before loading built-in linkml: schemas" in {
      val localTypes =
        """id: https://w3id.org/linkml/types
          |name: types
          |types:
          |  string:
          |    uri: xsd:string
          |    base: str
          |""".stripMargin
      val importer = ImportMapImporter(
        importMap("https://w3id.org/linkml/types" -> "local/types.yaml"),
        MapImporter(
          "model.yaml" -> schemaText("model", imports = Seq("linkml:types")),
          "local/types.yaml" -> localTypes,
        ),
      )
      inside(SchemaView.loadSchemas("model.yaml", importer)) { case Right(schemas) =>
        schemas.map(_.name) shouldBe Seq("model", "types")
        schemas(1).types.keySet shouldBe Set("string")
      }
    }

    "let the outer map of stacked maps have precedence" in {
      val inner = ImportMapImporter(
        importMap(
          "https://example.org/units" -> "inner/units.yaml",
          "https://example.org/outer-units" -> "outer/units.yaml",
        ),
        MapImporter(
          "model.yaml" -> schemaText("model", imports = Seq("https://example.org/units")),
          "inner/units.yaml" -> schemaText("inner_units"),
          "outer/units.yaml" -> schemaText("outer_units"),
        ),
      )
      loadedNames("model.yaml", inner) shouldBe Seq("model", "inner_units")
      // The inner map only sees the target of the outer one, which it maps again.
      val outer =
        ImportMapImporter(
          importMap("https://example.org/units" -> "https://example.org/outer-units"),
          inner,
        )
      loadedNames("model.yaml", outer) shouldBe Seq("model", "outer_units")
    }

    "report a chain that leads back to a key already seen" in {
      val importer = ImportMapImporter(
        importMap(
          "https://example.org/a" -> "https://example.org/b",
          "https://example.org/b" -> "https://example.org/a",
        ),
        MapImporter("model.yaml" -> schemaText("model", imports = Seq("https://example.org/a"))),
      )
      loadError("model.yaml", importer).importUri shouldBe "https://example.org/a.yaml"
    }
  }

  "loading two files with the same schema id" should {
    def sameId(title: String): String =
      s"id: https://example.org/units/core\nname: units\ntitle: $title\n"

    "report both files when an import map misses one of them" in {
      val importer = ImportMapImporter(
        importMap("https://example.org/units/core" -> "deps/units.yaml"),
        MapImporter(
          "model.yaml" -> schemaText(
            "model",
            imports = Seq("https://example.org/units/core", "./local/units"),
          ),
          "deps/units.yaml" -> sameId("new"),
          "local/units.yaml" -> sameId("stale"),
        ),
      )
      val issue = loadError("model.yaml", importer)
      issue.importUri shouldBe "local/units.yaml"
      issue.reason should include("'https://example.org/units/core'")
      issue.reason should include("'deps/units.yaml'")
      issue.reason should include("'local/units.yaml'")
      issue.location.schemaId.map(_.original) shouldBe Some("https://example.org/units/core")
    }

    "report them without an import map too" in {
      val importer = MapImporter(
        "model.yaml" -> schemaText(
          "model",
          imports = Seq("https://example.org/units/core", "core"),
        ),
        "https://example.org/units/core.yaml" -> sameId("new"),
        "core.yaml" -> sameId("stale"),
      )
      loadError("model.yaml", importer).reason should include("'core.yaml'")
    }

    "not report one file that is reached through different names" in {
      val importer = ImportMapImporter(
        importMap("https://example.org/units/core" -> "deps/units.yaml"),
        MapImporter(
          "model.yaml" -> schemaText(
            "model",
            imports = Seq("https://example.org/units/core", "deps/units", "./deps/units.yml"),
          ),
          "deps/units.yaml" -> sameId("new"),
        ),
      )
      loadedNames("model.yaml", importer) shouldBe Seq("model", "units")
    }

    "not report two files with the same content" in {
      val importer = MapImporter(
        "model.yaml" -> schemaText("model", imports = Seq("a/units", "b/units")),
        "a/units.yaml" -> sameId("new"),
        "b/units.yaml" -> sameId("new"),
      )
      loadedNames("model.yaml", importer) shouldBe Seq("model", "units", "units")
    }
  }

  "loading without an import map" should {
    "load a CURIE import from the URI it expands to" in {
      val importer = MapImporter(
        "model.yaml" -> schemaText(
          "model",
          prefixes = Map("units" -> "https://example.org/units/"),
          imports = Seq("units:core"),
        ),
        "https://example.org/units/core.yaml" -> schemaText("units"),
      )
      loadedNames("model.yaml", importer) shouldBe Seq("model", "units")
    }

    "report a CURIE import with an unknown prefix as an import error" in {
      val importer = MapImporter("model.yaml" -> schemaText("model", imports = Seq("units:core")))
      val issue = loadError("model.yaml", importer)
      issue.importUri shouldBe "units:core"
      issue.reason should include("'units'")
      issue.reason should include("https://neverblink.eu/linkml/import-map/model")
      inside(SchemaView.loadSchemaViewFromUri("model.yaml", importer)) {
        case Left(Seq(_: SchemaImportError)) =>
      }
    }

    "use an absolute import path as it is" in {
      val importer = MapImporter(
        "a/model.yaml" -> schemaText("model", imports = Seq("/schemas/core")),
        "/schemas/core.yaml" -> schemaText("core"),
      )
      loadedNames("a/model.yaml", importer) shouldBe Seq("model", "core")
    }

    "load an import written as a Windows path with a drive letter" in {
      val importer = MapImporter(
        "C:\\a\\model.yaml" -> schemaText("model", imports = Seq("D:\\schemas\\core")),
        "D:\\schemas\\core.yaml" -> schemaText("core"),
      )
      loadedNames("C:\\a\\model.yaml", importer) shouldBe Seq("model", "core")
    }

    "load a schema view from a Windows path whose imports are written with /" in {
      val importer = MapImporter(
        "C:\\a\\model.yaml" ->
          schemaText("model", imports = Seq("sub/core", "../shared/units")),
        "C:/a/sub/core.yaml" -> schemaText("core", imports = Seq("../../shared/units")),
        "C:/shared/units.yaml" -> schemaText("units"),
      )
      inside(SchemaView.loadSchemaViewFromUri("C:\\a\\model.yaml", importer)) { case Right(view) =>
        view.schemas.map(_.name) shouldBe Seq("model", "core", "units")
      }
    }
  }
}

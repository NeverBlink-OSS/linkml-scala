package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.validation.{SchemaImportError, SchemaParseError, UnexpectedError}
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ImporterSpec extends AnyWordSpec, Matchers, Inside {

  private val validSchema =
    """id: https://neverblink.eu/linkml/importer/test/
      |name: test
      |""".stripMargin

  private val exactSchema =
    """id: https://neverblink.eu/linkml/importer/exact/
      |name: exact
      |""".stripMargin

  "Importer.parseSchema" should {
    "return the schema for parseable YAML" in {
      FileSystemImporter.parseSchema(validSchema).map(_.name) shouldBe Right("test")
    }

    "return a SchemaParseError for unparseable YAML" in {
      val broken =
        """id: https://neverblink.eu/linkml/importer/test/
          |name: test
          |classes:
          |  SomeClass:
          |    slots: [a, b
          |""".stripMargin

      inside(FileSystemImporter.parseSchema(broken, "some/uri.yaml")) { case Left(issue) =>
        issue shouldBe a[SchemaParseError]
        issue.sourceUri shouldBe "some/uri.yaml"
        issue.parserMessage should not be empty
        issue.message shouldBe None
        SchemaIssues.description(issue.infer()) should startWith("Cannot parse schema:")
        SchemaIssues.verbose(issue.infer()) should include("some/uri.yaml")
      }
    }

    "pin a YAML syntax error to its 1-based position" in {
      // The unclosed flow sequence is on line 5, so the parser fails at the line below it.
      val broken =
        """id: https://neverblink.eu/linkml/importer/test/
          |name: test
          |classes:
          |  SomeClass:
          |    slots: [a, b
          |""".stripMargin

      inside(FileSystemImporter.parseSchema(broken)) { case Left(issue) =>
        inside(issue.location.codeRegion) { case Some(region) =>
          region.startLine shouldBe 6
          region.startColumn shouldBe 1
          // The offending token is a zero-width block end, so the parser reports no end position.
          region.endLine shouldBe None
          region.endColumn shouldBe None
        }
      }
    }

    "pin a decoding error to the position of the offending node" in {
      // `classes` must be a mapping, and the bad value sits on line 3, column 10.
      val notASchema =
        """id: https://neverblink.eu/linkml/importer/test/
          |name: test
          |classes: not-a-mapping
          |""".stripMargin

      inside(FileSystemImporter.parseSchema(notASchema)) { case Left(issue) =>
        inside(issue.location.codeRegion) { case Some(region) =>
          region.startLine shouldBe 3
          region.startColumn shouldBe 10
          // `end_column` is exclusive, so a node filling the rest of line 3 ends at line 4 column 1.
          region.endLine shouldBe Some(4)
          region.endColumn shouldBe Some(1)
        }
      }
    }

    "leave the code region empty when the parser reports no position" in {
      inside(FileSystemImporter.parseSchema("foo: *undefined_anchor")) { case Left(issue) =>
        issue.location.codeRegion shouldBe None
      }
    }

    "return a SchemaParseError when the YAML parses but does not decode" in {
      val notASchema =
        """id: https://neverblink.eu/linkml/importer/test/
          |name: test
          |classes: not-a-mapping
          |""".stripMargin

      inside(FileSystemImporter.parseSchema(notASchema)) { case Left(issue) =>
        issue shouldBe a[SchemaParseError]
        issue.parserMessage should not be empty
      }
    }
  }

  "Importer.readSchema" should {
    "return a SchemaImportError when the schema text cannot be read" in {
      inside(FileSystemImporter.readSchema("/does/not/exist.yaml")) { case Left(issue) =>
        issue shouldBe a[SchemaImportError]
        SchemaIssues.description(issue.asInstanceOf[SchemaImportError].infer()) should
          include("/does/not/exist.yaml")
      }
    }

    "return a SchemaParseError when the text is readable but unparseable" in {
      val importer = MapImporter("broken.yaml" -> "classes: [a, b")
      inside(importer.readSchema("broken.yaml")) { case Left(issue) =>
        issue shouldBe a[SchemaParseError]
      }
    }

    "return the schema when the text is readable and parseable" in {
      val importer = MapImporter("ok.yaml" -> validSchema)
      importer.readSchema("ok.yaml").map(_.name) shouldBe Right("test")
    }
  }

  "an import map" should {
    "find a key that was written without the .yaml extension" in {
      val importer = MapImporter("ok" -> validSchema)
      importer.readSchema("ok.yaml").map(_.name) shouldBe Right("test")
    }

    "leave a key that already ends in .yml alone" in {
      val importer = MapImporter("ok.yml" -> validSchema)
      importer.readSchema("ok.yml").map(_.name) shouldBe Right("test")
      importer.readSchema("ok.yaml") should matchPattern { case Left(_: SchemaImportError) => }
    }

    "prefer the key spelled exactly as it is looked up" in {
      val importer = MapImporter("core" -> validSchema, "core.yaml" -> exactSchema)
      importer.readSchema("core.yaml").map(_.name) shouldBe Right("exact")
    }

    "resolve an import written without the .yaml extension against such a key" in {
      val importer = MapImporter(
        "main" ->
          """id: https://neverblink.eu/linkml/importer/main/
            |name: main
            |imports:
            |  - core
            |""".stripMargin,
        "core" -> validSchema,
      )
      inside(SchemaView.loadSchemaViewFromUri("main", importer = importer)) { case Right(view) =>
        view.schemas.map(_.name) should contain allOf ("main", "test")
      }
    }
  }

  "a relative import" should {
    def importing(dir: String, sep: String): MapImporter = MapImporter(
      s"$dir${sep}main.yaml" ->
        """id: https://neverblink.eu/linkml/importer/main/
          |name: main
          |imports:
          |  - core
          |""".stripMargin,
      s"$dir${sep}core.yaml" -> validSchema,
    )

    def loadsBoth(dir: String, sep: String): Unit =
      inside(SchemaView.loadSchemaViewFromUri(s"$dir${sep}main.yaml", importing(dir, sep))) {
        case Right(view) => view.schemas.map(_.name) should contain allOf ("main", "test")
      }

    "resolve against a directory written with forward slashes" in {
      loadsBoth("schemas", "/")
    }

    "resolve against a directory written with backslashes" in {
      loadsBoth("C:\\schemas", "\\")
    }

    "resolve against the directory part of a URL" in {
      loadsBoth("https://example.org/schemas", "/")
    }
  }

  "Importer.normalizePath" should {
    "collapse dot segments and repeated separators" in {
      Importer.normalizePath("dir/./sub/../a.yaml") shouldBe "dir/a.yaml"
      Importer.normalizePath("./dir//a.yaml") shouldBe "dir/a.yaml"
      Importer.normalizePath("C:\\schemas\\sub\\..\\a.yaml") shouldBe "C:\\schemas\\a.yaml"
    }

    "keep leading parent segments of a relative path" in {
      Importer.normalizePath("../../dir/../a.yaml") shouldBe "../../a.yaml"
    }

    "not climb above the root of an absolute path or a URL" in {
      Importer.normalizePath("/../a.yaml") shouldBe "/a.yaml"
      Importer.normalizePath("https://example.org/x/../../a.yaml") shouldBe
        "https://example.org/a.yaml"
    }

    "leave URIs without a path alone" in {
      Importer.normalizePath("linkml:types") shouldBe "linkml:types"
      Importer.normalizePath("urn:x:./y") shouldBe "urn:x:./y"
    }
  }

  "importing one schema through differently spelled paths" should {
    def reads(root: String, files: (String, String)*): Seq[String] = {
      val log = Seq.newBuilder[String]
      val map = MapImporter(files*)
      val importer = new StringImporter {
        def read(path: String): String = {
          log += path
          map.read(path)
        }
      }
      SchemaView.loadSchemas(root, importer).isRight shouldBe true
      log.result()
    }

    def schema(name: String, imports: String*): String =
      s"id: https://neverblink.eu/linkml/importer/$name/\nname: $name\n" +
        imports.map(i => s"  - $i\n").mkString(if (imports.isEmpty) "" else "imports:\n", "", "")

    "load it once when reached through a parent directory" in {
      reads(
        "b.yaml",
        "b.yaml" -> schema("b", "./dir/a2", "./common/c"),
        "dir/a2.yaml" -> schema("a2", "../common/c"),
        "common/c.yaml" -> schema("c"),
      ) shouldBe Seq("b.yaml", "dir/a2.yaml", "common/c.yaml")
    }

    "load it once when reached through dot segments or doubled separators" in {
      reads(
        "b.yaml",
        "b.yaml" -> schema("b", "dir/a1", "dir//a1", "dir/./a1", "dir/sub/../a1"),
        "dir/a1.yaml" -> schema("a1"),
      ) shouldBe Seq("b.yaml", "dir/a1.yaml")
    }

    "not load the root again when an import points back at it" in {
      reads(
        "./b.yaml",
        "./b.yaml" -> schema("b", "dir/a1"),
        "dir/a1.yaml" -> schema("a1", "../b"),
      ) shouldBe Seq("b.yaml", "dir/a1.yaml")
    }
  }

  "SchemaView loading" should {
    "return the parse issue rather than throwing" in {
      inside(SchemaView.loadSchemaViewFromString("classes: [a, b")) { case Left(problems) =>
        problems should have size 1
        problems.head shouldBe a[SchemaParseError]
      }
    }

    "return the import issue rather than throwing" in {
      val importer = MapImporter(
        "main.yaml" ->
          """id: https://neverblink.eu/linkml/importer/test/
            |name: test
            |imports:
            |  - i_do_not_exist
            |""".stripMargin,
      )
      inside(SchemaView.loadSchemaViewFromUri("main.yaml", importer = importer)) {
        case Left(problems) =>
          problems.head shouldBe a[SchemaImportError]
          SchemaIssues.description(problems.head.infer()) should include("i_do_not_exist")
      }
    }

    "report an error the validator does not model as an UnexpectedError" in {
      val schema =
        """id: https://neverblink.eu/linkml/importer/test/
          |name: test
          |prefixes:
          |  bad: "https://example.org/ has a space"
          |default_prefix: bad
          |classes:
          |  SomeClass:
          |""".stripMargin
      inside(SchemaView.loadSchemaViewFromString(schema)) { case Left(problems) =>
        problems should have size 1
        problems.head shouldBe a[UnexpectedError]
        SchemaIssues.description(problems.head.infer()) should include("Unexpected error")
        SchemaIssues.description(problems.head.infer()) should include("has a space")
      }
    }

    "report a bad prefix as an UnexpectedError even when the schema has imports" in {
      // With imports, the prefix map is built while resolving them - before a view exists - so the
      // guard has to cover the whole load, not just view construction.
      val schema =
        """id: https://neverblink.eu/linkml/importer/test/
          |name: test
          |prefixes:
          |  linkml: "https://w3id.org/linkml/ has a space"
          |imports:
          |  - linkml:types
          |classes:
          |  SomeClass:
          |""".stripMargin
      inside(SchemaView.loadSchemaViewFromString(schema)) { case Left(problems) =>
        problems.head shouldBe a[UnexpectedError]
        SchemaIssues.description(problems.head.infer()) should include("has a space")
      }
    }

    "return the fatal validation problems rather than throwing" in {
      // Parses and imports fine, but references an undefined element.
      val schema =
        """id: https://neverblink.eu/linkml/importer/test/
          |name: test
          |slots:
          |  wrong:
          |    range: i_am_not_defined
          |""".stripMargin
      inside(SchemaView.loadSchemaViewFromString(schema)) { case Left(problems) =>
        problems should not be empty
        SchemaIssues.description(problems.head.infer()) should include("i_am_not_defined")
      }
    }

    "return the view for a schema that loads cleanly" in {
      SchemaView.loadSchemaViewFromString(validSchema).map(_.root.name) shouldBe Right("test")
    }
  }
}

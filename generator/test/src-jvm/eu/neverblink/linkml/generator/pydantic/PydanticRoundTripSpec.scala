package eu.neverblink.linkml.generator.pydantic

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.cli.Validate
import eu.neverblink.linkml.generator.DocumentModels.*
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try

/** Loads the catalogue's JSON data with the generated Python classes and real pydantic. Valid data
  * must load, dump back to the same JSON (or, where noted, equivalent JSON), and the dump must
  * validate against the JSON Schema of the same model. Invalid data must not load.
  *
  * Needs a `.venv` in the repository with pydantic and jsonschema, from `requirements.txt`.
  */
class PydanticRoundTripSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  import PydanticRoundTripSpec.*

  private val repoRoot: os.Path =
    Option(System.getenv("MILL_WORKSPACE_ROOT")).map(os.Path(_)).getOrElse(os.pwd)

  val inCi: Boolean = Option(System.getenv("CI")).exists(_.nonEmpty)

  /** The Python interpreter to run the checks with, if it has pydantic and jsonschema. */
  private lazy val python: Option[os.Path] = {
    val python = repoRoot / ".venv" / "bin" / "python"
    Option.when(
      os.exists(python) &&
        os.call((python, "-c", "import pydantic, jsonschema"), check = false).exitCode == 0,
    )(python)
  }

  override val skipModels: Map[String, String] = Map(
    "unionRange" -> "Not yet implemented: LNK-100",
  )

  // Known expressiveness limitations
  private val notExact: Map[(String, String), String] = Map(
    "compactDictDocument" -> "entries" -> "An entry that repeats its key is reserialized without it",
    "simpleDictDocument" -> "entries" ->
      "A simple dict entry written as an object is reserialized in the short form",
  )

  /** Invalid documents that an option makes valid, by variant, model and document. */
  private val validIn: Map[(String, String, String), String] = {
    val unknownKey = "With open, an unknown key is allowed"
    Map(
      ("open", "aliases", "unaliasedSlotName") -> unknownKey,
      ("open", "compactDictDocument", "unknownKey") -> unknownKey,
      ("open", "simpleDictDocument", "unknownKey") -> unknownKey,
    )
  }

  private val variants: Seq[(String, PydanticGenerator.Options, JsonSchemaGenerator.Options)] = Seq(
    ("default", PydanticGenerator.Options(), JsonSchemaGenerator.Options()),
    (
      "includeNull",
      PydanticGenerator.Options(includeNull = true),
      JsonSchemaGenerator.Options(includeNull = true),
    ),
    ("open", PydanticGenerator.Options(open = true), JsonSchemaGenerator.Options(open = true)),
  )

  private lazy val outDir: os.Path = os.temp.dir(prefix = "linkml-pydantic-")

  /** The models to check, by variant and model name. */
  private lazy val generated: Map[(String, String), Try[String]] =
    (for
      (variant, options, jsonOptions) <- variants
      model <- catalogue ++ documentForms
    yield {
      val dir = outDir / variant / safeName(model.name)
      val source = Try {
        given SchemaView = model.sv
        val source = PydanticGenerator().serialize(options)
        os.write.over(dir / "model.py", source, createFolders = true)
        os.write.over(dir / "schema.json", JsonSchemaGenerator().serialize(jsonOptions))
        for
          (valid, documents) <- Seq(true -> model.valid, false -> model.invalid)
          (name, json) <- documents
        do
          os.write.over(
            dir / s"${if valid then "valid" else "invalid"}_${safeName(name)}.json",
            json,
          )
        source
      }
      (variant, model.name) -> source
    }).toMap

  /** What the checker found, by variant and model name. */
  private lazy val results: Map[(String, String), ModelResult] = {
    val py = python.getOrElse(throw IllegalStateException("no Python"))
    val manifest = for
      (variant, _, _) <- variants
      model <- catalogue ++ documentForms
      source <- generated((variant, model.name)).toOption.toSeq
    yield {
      val dir = outDir / variant / safeName(model.name)
      val rootName = model.sv.treeRoot.map { root =>
        val name = PydanticGenerator(using model.sv).className(root)
        if source.contains(s"\nclass ${name}Document(") then name.concat("Document") else name
      }
      ManifestModel(
        id = s"$variant/${model.name}",
        module = s"m_${variant}_${safeName(model.name)}",
        file = (dir / "model.py").toString,
        schema = (dir / "schema.json").toString,
        root = rootName,
        documents = (model.valid.map(d => s"valid_${safeName(d._1)}") ++
          model.invalid.map(d => s"invalid_${safeName(d._1)}"))
          .map(n => ManifestDocument(n, (dir / s"$n.json").toString)),
      )
    }
    os.write.over(outDir / "manifest.json", writeToString(manifest))
    os.write.over(outDir / "check.py", checker)
    val result = os.call(
      (py, "check.py", "manifest.json", "results.json"),
      cwd = outDir,
      check = false,
      stderr = os.Pipe,
    )
    if result.exitCode != 0 then
      throw RuntimeException(s"The checker failed:\n${result.out.text()}${result.err.text()}")
    readFromString[Map[String, ModelResult]](os.read(outDir / "results.json")).map { (id, r) =>
      val Array(variant, name) = id.split("/", 2)
      (variant, name) -> r
    }
  }

  private def requirePython(): Unit =
    if python.isEmpty then {
      if inCi then fail("No .venv with pydantic and jsonschema")
      else cancel("Create a .venv from requirements.txt to enable this test")
    }

  /** The checker's result for one model, after checking that it generated and imported. */
  private def modelResult(variant: String, model: String): ModelResult = {
    requirePython()
    generated((variant, model)).get
    val result = results((variant, model))
    result.error.foreach(e => fail(s"Importing the generated module failed:\n$e"))
    result
  }

  private def checkValid(variant: String, model: String, document: String): Unit = {
    val result = modelResult(variant, model)
    val doc = result.documents.getOrElse(
      s"valid_${safeName(document)}",
      cancel("The model has no tree root"),
    )
    doc.crash.foreach(c => fail(s"Python failed:\n$c"))
    withClue(doc.error.getOrElse(""))(doc.loaded shouldBe true)
    withClue(s"The dump ${doc.dump.getOrElse("")}: ")(doc.schemaErrors shouldBe empty)
    withClue("Loading the dump again changes it: ")(doc.stable shouldBe Some(true))
    if !notExact.contains((model, document)) then
      withClue(s"The dump ${doc.dump.getOrElse("")} differs from the input: ")(
        doc.exact shouldBe Some(true),
      )
  }

  private def checkInvalid(variant: String, model: String, document: String): Unit = {
    validIn.get((variant, model, document)).foreach(reason => cancel(reason))
    val result = modelResult(variant, model)
    val doc = result.documents.getOrElse(
      s"invalid_${safeName(document)}",
      cancel("The model has no tree root"),
    )
    doc.crash.foreach(c => fail(s"Python failed:\n$c"))
    withClue(s"It loaded and dumped as ${doc.dump.getOrElse("")}: ")(doc.loaded shouldBe false)
  }

  "Pydantic generator" should {
    for (variant, _, _) <- variants do
      s"generate Python ($variant)" when {
        for entry <- ModelCatalogue.all do
          s"model '${entry.name}'" should {
            "import" in {
              assume(!skipModels.contains(entry.name), skipModels.getOrElse(entry.name, ""))
              modelResult(variant, entry.name)
            }
            for instance <- entry.validInstances.filter(_.json.isDefined).distinct do
              s"load and dump valid instance '${instance.name}'" in {
                processSkip(entry, instance)
                checkValid(variant, entry.name, instance.name)
              }
            for instance <- entry.invalidInstances.filter(_.json.isDefined).distinct do
              s"reject invalid instance '${instance.name}'" in {
                processSkip(entry, instance)
                checkInvalid(variant, entry.name, instance.name)
              }
          }

        for model <- documentForms do
          s"load a document of model '${model.name}'" when {
            for (document, _) <- model.valid do
              s"valid document '$document'" in checkValid(variant, model.name, document)
            for (document, _) <- model.invalid do
              s"invalid document '$document'" in checkInvalid(variant, model.name, document)
          }
      }

    // Dogfooding test
    "load real LinkML-Scala validation reports" in {
      requirePython()
      given SchemaView = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromUri((repoRoot / "model" / "issue-types.yaml").toString),
      )
      val dir = os.temp.dir(prefix = "linkml-pydantic-report-")
      os.write(dir / "report.py", PydanticGenerator().serialize())
      val schemas = brokenSchemas.zipWithIndex.map { (schema, i) =>
        val path = dir / s"schema$i.yaml"
        os.write(path, schema)
        path.toString
      }
      val (reports, _) = Validate.runTestCommand(List("validate", "--format", "json") ++ schemas)
      os.write(dir / "reports.json", reports)
      os.write(dir / "test_report.py", reportChecks)
      val result = os.call(
        (python.get, "-W", "error", "test_report.py"),
        cwd = dir,
        check = false,
        stderr = os.Pipe,
      )
      withClue(result.out.text() + result.err.text())(result.exitCode shouldBe 0)
      result.out.text().trim.toInt should be > 3
    }

    "behave as plain pydantic classes in Python code" in {
      requirePython()
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(behaviourSchema))
      val dir = os.temp.dir(prefix = "linkml-pydantic-behaviour-")
      os.write(dir / "zoo.py", PydanticGenerator(using sv).serialize())
      os.write(dir / "test_zoo.py", behaviourChecks)
      val result =
        os.call(
          (python.get, "-W", "error", "test_zoo.py"),
          cwd = dir,
          check = false,
          stderr = os.Pipe,
        )
      withClue(result.out.text() + result.err.text())(result.exitCode shouldBe 0)
    }
  }
}

object PydanticRoundTripSpec {

  final case class ManifestDocument(name: String, file: String)

  final case class ManifestModel(
      id: String,
      module: String,
      file: String,
      schema: String,
      root: Option[String],
      documents: Seq[ManifestDocument],
  )

  final case class DocumentResult(
      loaded: Boolean = false,
      error: Option[String] = None,
      crash: Option[String] = None,
      dump: Option[String] = None,
      schemaErrors: Seq[String] = Nil,
      stable: Option[Boolean] = None,
      exact: Option[Boolean] = None,
  )

  final case class ModelResult(
      error: Option[String] = None,
      documents: Map[String, DocumentResult] = Map.empty,
  )

  given JsonValueCodec[Seq[ManifestModel]] = JsonCodecMaker.make
  given JsonValueCodec[Map[String, ModelResult]] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false))

  /** Imports each generated module and loads its documents with the tree root's class. */
  private val checker: String =
    """import importlib.util
      |import json
      |import sys
      |import traceback
      |import warnings
      |
      |import jsonschema
      |from pydantic import ValidationError
      |
      |warnings.simplefilter("error")
      |
      |with open(sys.argv[1]) as f:
      |    manifest = json.load(f)
      |
      |results = {}
      |for model in manifest:
      |    result = {"documents": {}}
      |    results[model["id"]] = result
      |    try:
      |        spec = importlib.util.spec_from_file_location(model["module"], model["file"])
      |        module = importlib.util.module_from_spec(spec)
      |        sys.modules[model["module"]] = module
      |        spec.loader.exec_module(module)
      |    except Exception:
      |        result["error"] = traceback.format_exc()
      |        continue
      |    if "root" not in model:
      |        continue
      |    root = getattr(module, model["root"])
      |    with open(model["schema"]) as f:
      |        schema = json.load(f)
      |    validator = jsonschema.Draft202012Validator(
      |        schema, format_checker=jsonschema.Draft202012Validator.FORMAT_CHECKER
      |    )
      |    for document in model.get("documents", []):
      |        with open(document["file"]) as f:
      |            text = f.read()
      |        outcome = {}
      |        result["documents"][document["name"]] = outcome
      |        try:
      |            loaded = root.model_validate_json(text)
      |        except ValidationError as e:
      |            outcome["error"] = str(e)
      |            continue
      |        except Exception:
      |            outcome["crash"] = traceback.format_exc()
      |            continue
      |        outcome["loaded"] = True
      |        try:
      |            dump = loaded.model_dump_json()
      |            outcome["dump"] = dump
      |            outcome["schemaErrors"] = [
      |                f"{list(e.absolute_path)}: {e.message}" for e in validator.iter_errors(json.loads(dump))
      |            ]
      |            outcome["stable"] = root.model_validate_json(dump).model_dump_json() == dump
      |            outcome["exact"] = json.loads(dump) == json.loads(text)
      |        except Exception:
      |            outcome["crash"] = traceback.format_exc()
      |
      |with open(sys.argv[2], "w") as f:
      |    json.dump(results, f)
      |""".stripMargin

  /** Schemas with problems of several kinds, to get reports with several issue types. */
  private val brokenSchemas: Seq[String] = Seq(
    """id: https://neverblink.eu/test/
      |name: test
      |default_range: string
      |types:
      |  string:
      |classes:
      |  SomeClass:
      |    class_uri: "not a curie!"
      |""".stripMargin,
    """id: https://neverblink.eu/test2/
      |name: test2
      |default_range: string
      |types:
      |  string:
      |classes:
      |  Root:
      |    tree_root: true
      |    attributes:
      |      thing:
      |        range: Missing
      |""".stripMargin,
    """id: https://neverblink.eu/test3/
      |name: test3
      |default_range: string
      |types:
      |  string:
      |classes:
      |  A:
      |    tree_root: true
      |  B:
      |    tree_root: true
      |  Zwölf:
      |""".stripMargin,
  )

  /** Loads each report, checks that every issue gets its own class and that it dumps back the same,
    * and prints how many issues there were.
    */
  private val reportChecks: String =
    """import json
      |
      |from report import SchemaValidationReport
      |
      |with open("reports.json") as f:
      |    reports = json.load(f)
      |
      |count = 0
      |for data in reports:
      |    report = SchemaValidationReport.model_validate_json(json.dumps(data))
      |    for issue, raw in zip(report.issues, data["issues"]):
      |        assert type(issue).__name__ == raw["issue_type"], (type(issue), raw)
      |        count += 1
      |    assert json.loads(report.model_dump_json()) == data, (report.model_dump_json(), data)
      |print(count)
      |""".stripMargin

  private val behaviourSchema: String =
    """id: https://example.org/zoo
      |name: zoo
      |prefixes:
      |  ex: https://example.org/zoo/
      |default_prefix: ex
      |imports:
      |  - linkml:types
      |classes:
      |  Zoo:
      |    tree_root: true
      |    attributes:
      |      animals:
      |        range: Animal
      |        multivalued: true
      |        inlined_as_list: true
      |      keepers:
      |        range: Keeper
      |        multivalued: true
      |        inlined: true
      |  Animal:
      |    abstract: true
      |    attributes:
      |      kind:
      |        designates_type: true
      |        required: true
      |      name:
      |        required: true
      |  Lion:
      |    is_a: Animal
      |  Parrot:
      |    is_a: Animal
      |  Keeper:
      |    attributes:
      |      id:
      |        identifier: true
      |      my-slot:
      |      since:
      |        range: date
      |""".stripMargin

  private val behaviourChecks: String =
    """from datetime import date
      |
      |from pydantic import ValidationError
      |
      |from zoo import Animal, Keeper, Lion, Parrot, Zoo
      |
      |
      |def rejects(f):
      |    try:
      |        f()
      |    except ValidationError:
      |        return
      |    raise AssertionError("expected a ValidationError")
      |
      |
      |# Loads by the JSON key or the Python name, dumps by the JSON key
      |keeper = Keeper(id="k1", my_slot="x", since=date(2020, 1, 2))
      |assert keeper.model_dump_json() == '{"id":"k1","my-slot":"x","since":"2020-01-02"}'
      |assert Keeper.model_validate_json('{"id":"k1","my-slot":"x"}').my_slot == "x"
      |loaded = Keeper.model_validate_json('{"id":"k1","my_slot":"x"}')
      |assert loaded.model_dump_json() == '{"id":"k1","my-slot":"x"}'
      |
      |# A designated class writes its type, and a union picks the class from it
      |assert Lion(name="Leo").model_dump_json() == '{"kind":"Lion","name":"Leo"}'
      |zoo = Zoo.model_validate_json('{"animals":[{"kind":"Parrot","name":"Polly"}]}')
      |assert type(zoo.animals[0]) is Parrot and isinstance(zoo.animals[0], Animal)
      |rejects(lambda: Zoo.model_validate_json('{"animals":[{"kind":"Animal","name":"A"}]}'))
      |
      |# Dict keys go into the objects, and out again
      |zoo = Zoo.model_validate_json('{"keepers":{"k1":{"my-slot":"x"}}}')
      |assert zoo.keepers["k1"].id == "k1"
      |assert zoo.model_dump_json() == '{"keepers":{"k1":{"my-slot":"x"}}}'
      |rejects(lambda: Zoo.model_validate_json('{"keepers":{"k1":{"id":"k2"}}}'))
      |
      |# A null loads, but is not dumped
      |assert Keeper.model_validate_json('{"id":"k1","since":null}').model_dump_json() == '{"id":"k1"}'
      |
      |# Assignments are checked too
      |def assign_number():
      |    keeper.since = 20200102
      |
      |
      |rejects(assign_number)
      |rejects(lambda: Keeper(id="k1", since=20200102))
      |""".stripMargin
}

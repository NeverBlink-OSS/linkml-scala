package eu.neverblink.ohi.cli

import eu.neverblink.linkml.cli.*
import eu.neverblink.linkml.tests.Resources
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.{Node, parseYaml}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.time.Year

class AppSpec extends AnyWordSpec, Matchers {

  private val engineCommands: Seq[(String, BaseCommand[?], String)] = Seq(
    ("linkml validate", Validate, "Validate one or more LinkML schemas"),
    ("linkml lint", Validate, "Validate one or more LinkML schemas"),
    ("linkml generate scala", Scala, "Generate Scala classes"),
    ("linkml generate json-schema", JsonSchema, "Generate JSON Schema"),
    ("linkml generate shacl", Shacl, "Generate SHACL shapes"),
    ("linkml generate rdfs", Rdfs, "Generate RDF schema"),
    ("linkml generate owl", Owl, "Generate an OWL 2 ontology"),
    ("linkml generate linkml", LinkMl, "Materialize a derived LinkML schema"),
    ("linkml generate frictionless", Frictionless, "Generate a Frictionless Data Package"),
    ("linkml generate graphql", GraphQl, "Generate a GraphQL Schema"),
    ("linkml generate typescript", TypeScript, "Generate TypeScript types"),
    ("linkml generate pydantic", Pydantic, "Generate Python classes based on pydantic"),
    ("linkml generate ossie", Ossie, "Generate an Apache Ossie ontology"),
    ("linkml generate er-diagram", ErDiagram, "Generate a Mermaid entity relationship diagram"),
    ("linkml generate translation", Translation, "Generate translation dictionaries"),
    ("linkml from ossie", FromOssie, "Read an Apache Ossie ontology"),
    ("linkml from owl", FromOwl, "Read an OWL ontology or RDFS vocabulary"),
  )
  private val versionNames =
    Seq("version", "v", "--version", "linkml version", "linkml v", "linkml --version")

  private def runApp(
      args: List[String],
      command: OhiApp => BaseCommand[?] = _.version,
  ): (String, String, Int) = {
    val rootOut = new ByteArrayOutputStream()
    val rootErr = new ByteArrayOutputStream()
    val out = new PrintStream(rootOut, true, "UTF-8")
    val err = new PrintStream(rootErr, true, "UTF-8")
    try {
      val app = new OhiApp(testMode = true, outStream = out, errStream = err)
      // Entry-point diagnostics and leaf commands use separate streams.
      val (leafOut, leafErr, code) =
        command(app).runTestCommandWithExitCode(args, app.main)
      (rootOut.toString("UTF-8") + leafOut, rootErr.toString("UTF-8") + leafErr, code)
    } finally {
      out.close()
      err.close()
    }
  }

  private def plain(out: String): String = out.replaceAll("\u001b\\[[0-9;]*m", "")

  private def fields(node: Node): Map[String, Node] =
    node.asInstanceOf[Node.MappingNode].mappings.map { (key, value) =>
      key.asInstanceOf[Node.ScalarNode].value -> value
    }.toMap

  private def scalar(node: Node): String = node.asInstanceOf[Node.ScalarNode].value

  private def parsed(document: String): Node = parseYaml(document).toOption.get

  private def withBasicModel(test: os.Path => Unit): Unit = {
    val dir = os.temp.dir(prefix = "ohi model ")
    try {
      val path = dir / "basic model.yaml"
      os.write(path, Resources.read("/models/basic/model.yaml"))
      test(path)
    } finally os.remove.all(dir)
  }

  "the Ohi entry point" should {
    "register the catalog commands, nested engine commands and six version aliases" in {
      new OhiApp().commands.flatMap(_.names.map(_.mkString(" "))) should contain theSameElementsAs
        (engineCommands.map(_._1) ++ versionNames ++ Seq(
          "search",
          "inspect",
          "get",
          "init",
          "add",
          "lock",
        ))
    }

    for (name, command, description) <- engineCommands do
      s"route $name help to its handler with the full Ohi usage path" in {
        val (out, err, code) = runApp(name.split(" ").toList :+ "--help", _ => command)
        code shouldBe 0
        err shouldBe empty
        plain(out) should include(s"Usage: ohi $name")
        out should include(description)
      }

    "retain Scala options in nested leaf help" in {
      val (out, err, code) = runApp(List("linkml", "generate", "scala", "--help"), _ => Scala)
      code shouldBe 0
      err shouldBe empty
      out should include("--package")
      out should include("--to")
      out should include("--clean")
    }

    for args <- Seq(List.empty[String], List("--help"), List("-h"), List("-help"), List("--usage"))
    do
      s"show Ohi usage and command inventory for root ${args.mkString(" ")}" in {
        val (out, err, code) = runApp(args)
        code shouldBe 0
        err shouldBe empty
        plain(out) should include("Usage: ohi <COMMAND>")
        engineCommands.foreach { (name, _, _) => plain(out) should include(name) }
        out should include("version")
      }

      s"show the same Ohi usage for bare linkml group ${args.mkString(" ")}" in {
        val (out, err, code) = runApp("linkml" :: args)
        code shouldBe 0
        err shouldBe empty
        out shouldBe runApp(List("--help"))._1
      }

    "reject a flat engine command instead of accepting an alternate layout" in {
      val (out, err, code) = runApp(List("generate", "scala", "model.yaml"))
      code shouldBe 1
      out shouldBe empty
      err should include("Unknown command: generate")
      err should include("Run 'ohi --help'")
    }

    "retain nonzero failure for an incomplete nested generator" in {
      val (out, err, code) = runApp(List("linkml", "generate"))
      code shouldBe 1
      out shouldBe empty
      err should include("Unknown command: linkml generate")
    }

    "name the unknown nested target without including its input filename" in {
      val (out, err, code) = runApp(List("linkml", "generate", "malbolge", "model.yaml"))
      code shouldBe 1
      out shouldBe empty
      err should include("Unknown command: linkml generate malbolge")
      err should not include "model.yaml"
      err should include("Run 'ohi --help'")
    }

    "name a root typo without including its input filename" in {
      val (out, err, code) = runApp(List("genrate", "model.yaml"))
      code shouldBe 1
      out shouldBe empty
      err should include("Unknown command: genrate")
      err should not include "model.yaml"
      err should include("Run 'ohi --help'")
    }
  }

  "Ohi version" should {
    for name <- versionNames do
      s"identify Ohi and preserve component and provenance information through $name" in {
        val (out, err, code) = runApp(name.split(" ").toList)
        code shouldBe 0
        err shouldBe empty
        out should startWith("ohi ")
        out should include("Metamodel")
        out should include("Scala")
        out should include("Runtime")
        out should include(s"Copyright (C) ${Year.now().getValue} NeverBlink and contributors")
        out should include("https://www.apache.org/licenses/LICENSE-2.0")
        out should include("https://github.com/NeverBlink-OSS/linkml-scala")
      }

      s"describe Ohi in $name help with its invoked usage path" in {
        val (out, err, code) = runApp(name.split(" ").toList :+ "--help")
        code shouldBe 0
        err shouldBe empty
        plain(out) should include(s"Usage: ohi $name")
        out should include("Print the version of ohi and its key components.")
      }

    for name <- Seq("version", "linkml version") do
      s"preserve the JVM BuildInfo JSON fields through $name" in {
        val (out, err, code) = runApp(name.split(" ").toList ++ List("--format", "json"))
        code shouldBe 0
        err shouldBe empty
        out.trim should startWith("{")
        out.trim should endWith("}")
        val info = fields(parsed(out))
        info.keySet shouldBe Set(
          "linkml_scala_version",
          "metamodel_version",
          "scala_version",
          "platform",
          "runtime",
        )
        scalar(info("platform")) shouldBe "JVM"
        Seq("linkml_scala_version", "metamodel_version", "scala_version", "runtime").foreach {
          key =>
            scalar(info(key)) should not be empty
        }
        out should not include "Copyright"
        out should not include "\u001b"
      }

    "reject an unknown output format with the existing handler's failure" in {
      val (out, err, code) = runApp(List("linkml", "version", "--format", "yaml"))
      code shouldBe 1
      out shouldBe empty
      err should include("Unknown format 'yaml'. Supported formats: terminal|json.")
    }
  }

  "Ohi engine forwarding" should {
    "keep a spaced validation path as one input and preserve its JSON report" in {
      withBasicModel { path =>
        val (out, err, code) =
          runApp(List("linkml", "validate", "--format", "json", path.toString), _ => Validate)
        withClue(s"stderr was: $err\n") { code shouldBe 0 }
        err shouldBe empty
        val reports = parsed(out).asInstanceOf[Node.SequenceNode].nodes
        reports should have size 1
        val report = fields(reports.head)
        report.keySet shouldBe Set("validation_run_id", "issues")
        scalar(report("validation_run_id")) shouldBe path.toString
        report("issues").asInstanceOf[Node.SequenceNode].nodes shouldBe empty
      }
    }

    "forward JSON Schema output options and replace an existing destination" in {
      withBasicModel { path =>
        val dest = path / os.up / "generated schema.json"
        os.write(dest, "filler\n" * 10_000)
        val (out, err, code) = runApp(
          List("linkml", "generate", "json-schema", "--to", dest.toString, path.toString),
          _ => JsonSchema,
        )
        withClue(s"stderr was: $err\n") { code shouldBe 0 }
        out shouldBe empty
        err shouldBe empty
        val written = os.read(dest)
        written should not include "filler"
        val schema = fields(fields(fields(parsed(written))("$defs"))("SomeClass"))
        fields(schema("properties")).keySet shouldBe Set("some_slot", "some_other_slot")
        schema("required").asInstanceOf[Node.SequenceNode].nodes.map(scalar) shouldBe Seq(
          "some_other_slot",
        )
      }
    }

    "forward the OWL importer and explicit schema identifier" in {
      val dir = os.temp.dir(prefix = "ohi-import")
      try {
        val path = dir / "book.ttl"
        os.write(
          path,
          """@prefix ex: <https://example.org/> .
            |ex:Book a <http://www.w3.org/2002/07/owl#Class> .
            |""".stripMargin,
        )
        val (out, err, code) = runApp(
          List(
            "linkml",
            "from",
            "owl",
            "--schema-id",
            "https://example.org/ohi-test",
            path.toString,
          ),
          _ => FromOwl,
        )
        withClue(s"stderr was: $err\n") { code shouldBe 0 }
        err shouldBe empty
        val schema = fields(parsed(out))
        scalar(schema("id")) shouldBe "https://example.org/ohi-test"
        fields(schema("classes")).keySet should contain("Book")
      } finally os.remove.all(dir)
    }
  }
}

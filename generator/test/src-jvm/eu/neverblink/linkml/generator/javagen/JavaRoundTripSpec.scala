package eu.neverblink.linkml.generator.javagen

import com.networknt.schema.{InputFormat, Schema, SchemaRegistry}
import com.networknt.schema.dialect.Dialects
import eu.neverblink.linkml.generator.DocumentModels.*
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.options.{JavaGeneratorOptions, JsonSchemaGeneratorOptions}
import eu.neverblink.linkml.cli.Validate
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URLClassLoader
import javax.tools.{DiagnosticCollector, JavaFileObject, ToolProvider}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Compiles the generated Java of the catalogue models with `javac`, and loads the catalogue's JSON
  * data with it, with Jackson 2 and Jackson 3. Valid data must load, dump back to the same JSON
  * (or, where noted, equivalent JSON), and the dump must validate against the JSON Schema of the
  * same model. Invalid data must not load.
  */
class JavaRoundTripSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  import JavaRoundTripSpec.*

  private val booleanExpressions = "Not checked: `all_of`, `none_of` and `exactly_one_of`"
  private val memberConstraints = "Not checked: the constraints of the members of a union"
  private val classUnion =
    "Not checked: an `any_of` with classes, whose value is the JSON as Jackson reads it"
  private val memberCardinality = "Not checked: cardinality in `any_of` members"

  /** Instances to skip: invalid ones that the classes accept, and valid ones that they reject. */
  override val skipInstances: Map[(String, String), String] = Map(
    "unionOf" -> "badPattern" -> memberConstraints,
    "unionOf" -> "negative" -> memberConstraints,
    "nestedExpressions" -> "largeInteger" -> memberConstraints,
    "allOf" -> "matchesFirstOnly" -> booleanExpressions,
    "allOfInheritance" -> "failsBase" -> booleanExpressions,
    "allOfInheritance" -> "failsSlotUsage" -> booleanExpressions,
    "noneOf" -> "matchesMember" -> booleanExpressions,
    "noneOfInheritance" -> "matchesBase" -> booleanExpressions,
    "noneOfInheritance" -> "matchesSlotUsage" -> booleanExpressions,
    "exactlyOneOf" -> "matchesBoth" -> booleanExpressions,
    "exactlyOneOf" -> "matchesNone" -> booleanExpressions,
    "exactlyOneOfInheritance" -> "matchesBothInBase" -> booleanExpressions,
    "exactlyOneOfInheritance" -> "matchesNone" -> booleanExpressions,
    "allOfMixins" -> "failsBase" -> booleanExpressions,
    "allOfMixins" -> "failsMixin" -> booleanExpressions,
    "noneOfMixins" -> "matchesBase" -> booleanExpressions,
    "noneOfMixins" -> "matchesMixin" -> booleanExpressions,
    "exactlyOneOfMixins" -> "matchesBothInBase" -> booleanExpressions,
    "exactlyOneOfMixins" -> "matchesNoneInMixin" -> booleanExpressions,
    "exactlyOneOfWidening" -> "matchesBothInBase" -> booleanExpressions,
    "exactlyOneOfWidening" -> "matchesNoneInSlotUsage" -> booleanExpressions,
    "allOfNoneOfChain" -> "failsBaseAllOf" -> booleanExpressions,
    "allOfNoneOfChain" -> "failsBaseNoneOf" -> booleanExpressions,
    "allOfNoneOfChain" -> "failsChildNoneOf" -> booleanExpressions,
    "allOfNoneOfChain" -> "failsGrandchildNoneOf" -> booleanExpressions,
    "memberCardinality" -> "twoStrings" -> memberCardinality,
    "memberCardinality" -> "integerAndString" -> memberCardinality,
    "memberCardinality" -> "oneInteger" -> memberCardinality,
    "anyOfInlinedList" -> "emptyObject" -> classUnion,
    "anyOfTypeOrClass" -> "integer" -> classUnion,
    "anyOfTypeOrClass" -> "objectWithoutName" -> classUnion,
    "importedOverride" -> "unaliasedLabel" ->
      "An alias set by a `slot_usage` adds a second property, as the field of the parent stays",
    "multivaluedOverride" -> "many" ->
      "A `slot_usage` that makes an inherited slot multivalued can't change the type of its field",
  )

  // Known expressiveness limitations
  private val notExact: Map[(String, String), String] = Map(
    "compactDictDocument" -> "entries" -> "An entry that repeats its key is reserialized without it",
    "simpleDictDocument" -> "entries" ->
      "A simple dict entry written as an object is reserialized in the short form",
    "identifierAlias" -> "withKey" -> "An entry that repeats its key is reserialized without it",
  )

  /** Invalid documents that are valid with an option, by variant, model and document. */
  private val validIn: Map[(String, String, String), String] = {
    val unknownKey = "With open, an unknown key is allowed"
    Map(
      ("open", "aliases", "unaliasedSlotName") -> unknownKey,
      ("open", "compactDictDocument", "unknownKey") -> unknownKey,
      ("open", "simpleDictDocument", "unknownKey") -> unknownKey,
      ("open", "identifierAlias", "unaliasedKey") -> unknownKey,
    )
  }

  private val variants: Seq[(String, JavaGeneratorOptions, JsonSchemaGeneratorOptions)] = Seq(
    ("default", JavaGeneratorOptions(), JsonSchemaGeneratorOptions()),
    ("strict", JavaGeneratorOptions(strict = true), JsonSchemaGeneratorOptions()),
    (
      "open",
      JavaGeneratorOptions(strict = true, open = true),
      JsonSchemaGeneratorOptions(open = true),
    ),
  )

  private val models: Seq[Model] = catalogue ++ documentForms

  private lazy val outDir: os.Path = os.temp.dir(prefix = "linkml-java-")

  private val schemas: SchemaRegistry = SchemaRegistry.withDialect(Dialects.getDraft202012)

  /** The generated files of each model, by variant and model name. */
  private lazy val generated: Map[(String, String), Try[Generated]] =
    (for
      (variant, options, jsonOptions) <- variants
      model <- models
    yield {
      val pkg = s"m_${variant}_${safeName(model.name)}"
      val result = Try {
        given SchemaView = model.sv
        val files = JavaGenerator().generate(options.copy(`package` = Some(pkg)))
        files.foreach((name, source) =>
          os.write.over(outDir / variant / "src" / pkg / name, source, createFolders = true),
        )
        val root = model.sv.treeRoot.map { root =>
          val name = JavaGenerator().className(root)
          val document = name.concat("Document")
          s"$pkg.${if files.exists(_._1 == s"$document.java") then document else name}"
        }
        Generated(pkg, root, schemas.getSchema(JsonSchemaGenerator().serialize(jsonOptions)))
      }
      (variant, model.name) -> result
    }).toMap

  /** The compiled classes of each variant, with the diagnostics of each model. */
  private lazy val compiled: Map[String, Compiled] = variants.map { (variant, _, _) =>
    val packages = models.flatMap(m => generated((variant, m.name)).toOption.map(m.name -> _.pkg))
    val classes = outDir / variant / "classes"
    os.makeDir.all(classes)
    // Compile everything at once; then once more without the models that javac rejected, so that
    // one broken model does not take the others with it
    def sources(pkgs: Seq[String]) = pkgs.flatMap(p => os.list(outDir / variant / "src" / p))
    val first = javac(sources(packages.map(_._2)), classes, Seq(annotationsJar))
    val broken = first.collect { case d if d.error => d.pkg }.toSet
    val diagnostics =
      if broken.isEmpty then first
      else
        first ++ javac(sources(packages.map(_._2).filterNot(broken)), classes, Seq(annotationsJar))
          .filter(_.error)
    val loader = URLClassLoader(Array(classes.toNIO.toUri.toURL), getClass.getClassLoader)
    variant -> Compiled(
      loader,
      packages.map((name, pkg) => name -> diagnostics.filter(_.pkg == pkg).distinct).toMap,
    )
  }.toMap

  /** The generated classes of a model, after checking that they generated and compiled cleanly. */
  private def modelClasses(variant: String, model: String): (Generated, ClassLoader) = {
    val gen = generated((variant, model)).get
    val result = compiled(variant)
    val diagnostics = result.diagnostics.getOrElse(model, Nil)
    withClue(diagnostics.map(_.message).mkString("javac:\n", "\n", "\n"))(
      diagnostics shouldBe empty,
    )
    (gen, result.loader)
  }

  private def rootClass(variant: String, model: String): Class[?] = {
    val (gen, loader) = modelClasses(variant, model)
    Class.forName(gen.root.getOrElse(cancel("The model has no tree root")), true, loader)
  }

  private def checkValid(variant: String, model: String, document: String, json: String): Unit = {
    val cls = rootClass(variant, model)
    val schema = generated((variant, model)).get.schema
    for jackson <- jacksons do
      withClue(s"${jackson.name}: ") {
        val outcome = jackson.load(cls, json)
        outcome.crash.foreach(c => fail(s"Java failed:\n$c"))
        withClue(outcome.error.getOrElse(""))(outcome.dump shouldBe defined)
        val dump = outcome.dump.get
        val errors = schema.validate(dump, InputFormat.JSON).asScala.map(_.toString)
        withClue(s"The dump $dump: ")(errors shouldBe empty)
        val again = jackson.load(cls, dump)
        withClue("Loading the dump again changes it: ")(again.dump shouldBe Some(dump))
        if !notExact.contains((model, document)) then
          withClue(s"The dump $dump differs from the input: ")(sameJson(dump, json) shouldBe true)
      }
  }

  private def isStrict(variant: String): Boolean =
    variants.exists((v, options, _) => v == variant && options.strict)

  private def checkInvalid(variant: String, model: String, document: String, json: String): Unit = {
    validIn.get((variant, model, document)).foreach(reason => cancel(reason))
    val cls = rootClass(variant, model)
    for jackson <- jacksons do
      withClue(s"${jackson.name}: ") {
        val outcome = jackson.load(cls, json)
        outcome.crash.foreach(c => fail(s"Java failed:\n$c"))
        // Without strict, Jackson converts values and the schema's constraints are not checked.
        // The strict variant checks the same document.
        if outcome.dump.isDefined && !isStrict(variant) then cancel("Only checked with strict")
        withClue(s"It loaded and dumped as ${outcome.dump.getOrElse("")}: ")(
          outcome.dump shouldBe empty,
        )
      }
  }

  private def compileAndRun(
      dir: os.Path,
      files: Seq[(String, String)],
      program: Option[(String, String)],
  ): Seq[String] = {
    files.foreach((name, source) =>
      os.write.over(dir / "src" / os.RelPath(name), source, createFolders = true),
    )
    val classes = dir / "classes"
    os.makeDir.all(classes)
    val generated =
      javac(files.map((name, _) => dir / "src" / os.RelPath(name)), classes, Seq(annotationsJar))
    withClue(generated.map(_.message).mkString("javac:\n", "\n", "\n"))(generated shouldBe empty)
    program.toSeq.flatMap { (name, template) =>
      jacksons.map { jackson =>
        val source = dir / "src" / s"${name}${jackson.suffix}.java"
        os.write.over(
          source,
          jackson.adapt(template).replace(s"class $name ", s"class $name${jackson.suffix} "),
        )
        val diagnostics =
          javac(Seq(source), classes, Seq(annotationsJar, classes.toString) ++ jackson.classPath)
        withClue(diagnostics.map(_.message).mkString("javac:\n", "\n", "\n"))(
          diagnostics.filter(_.error) shouldBe empty,
        )
        val loader = URLClassLoader(Array(classes.toNIO.toUri.toURL), getClass.getClassLoader)
        val main = Class.forName(s"$name${jackson.suffix}", true, loader).getMethod(
          "main",
          classOf[Array[String]],
        )
        try {
          main.invoke(null, Array.empty[String])
          ""
        } catch {
          case e: java.lang.reflect.InvocationTargetException =>
            val trace = java.io.StringWriter()
            e.getCause.printStackTrace(java.io.PrintWriter(trace))
            s"${jackson.name}: $trace"
        }
      }
    }.filter(_.nonEmpty)
  }

  "Java generator" should {
    for (variant, _, _) <- variants do
      s"generate Java ($variant)" when {
        for entry <- ModelCatalogue.all do
          s"model '${entry.name}'" should {
            "compile" in {
              assume(!skipModels.contains(entry.name), skipModels.getOrElse(entry.name, ""))
              modelClasses(variant, entry.name)
            }
            for instance <- entry.validInstances.filter(_.json.isDefined).distinct do
              s"load and dump valid instance '${instance.name}'" in {
                processSkip(entry, instance)
                checkValid(variant, entry.name, instance.name, instance.json.get)
              }
            for instance <- entry.invalidInstances.filter(_.json.isDefined).distinct do
              s"reject invalid instance '${instance.name}'" in {
                processSkip(entry, instance)
                checkInvalid(variant, entry.name, instance.name, instance.json.get)
              }
          }

        for model <- documentForms do
          s"load a document of model '${model.name}'" when {
            for (document, json) <- model.valid do
              s"valid document '$document'" in checkValid(variant, model.name, document, json)
            for (document, json) <- model.invalid do
              s"invalid document '$document'" in checkInvalid(variant, model.name, document, json)
          }
      }

    // Dogfooding test
    "load real ohi LinkML validation reports" in {
      given SchemaView = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromUri((repoRoot / "model" / "issue-types.yaml").toString),
      )
      val dir = os.temp.dir(prefix = "linkml-java-report-")
      val files = JavaGenerator().generate(JavaGeneratorOptions(`package` = Some("report")))
      compileAndRun(dir / "report", files, None)
      val loader = URLClassLoader(
        Array((dir / "report" / "classes").toNIO.toUri.toURL),
        getClass.getClassLoader,
      )
      val cls = Class.forName("report.SchemaValidationReport", true, loader)
      val schemas = brokenSchemas.zipWithIndex.map { (schema, i) =>
        val path = dir / s"schema$i.yaml"
        os.write(path, schema)
        path.toString
      }
      val (reports, _) = Validate.runTestCommand(List("validate", "--format", "json") ++ schemas)
      val raw = trees.readTree(reports)
      raw.size should be > 2
      var issues = 0
      for i <- 0 until raw.size; jackson <- jacksons do
        withClue(s"${jackson.name}: ") {
          val json = trees.writeValueAsString(raw.get(i))
          val report = jackson.read(cls, json)
          val loaded =
            cls.getMethod("getIssues").invoke(report).asInstanceOf[java.util.List[AnyRef]]
          loaded.asScala.zipWithIndex.foreach { (issue, j) =>
            issue.getClass.getSimpleName shouldBe raw.get(i).get("issues").get(j).get(
              "issue_type",
            ).asString
            issues += 1
          }
          sameJson(jackson.write(report), json) shouldBe true
        }
      issues should be > 6
    }

    for strict <- Seq(false, true) do
      s"behave as plain Java classes in Java code (strict = $strict)" in {
        val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(behaviourSchema))
        val dir = os.temp.dir(prefix = "linkml-java-behaviour-")
        val options = JavaGeneratorOptions(`package` = Some("zoo"), strict = strict)
        val files = JavaGenerator(using sv).generate(options)
        val program = behaviourChecks.replace("STRICT = false", s"STRICT = $strict")
        val failures =
          compileAndRun(dir, files.map((n, s) => s"zoo/$n" -> s), Some("Behaviour" -> program))
        failures shouldBe empty
      }
  }
}

object JavaRoundTripSpec {

  final case class Generated(pkg: String, root: Option[String], schema: Schema)

  private val repoRoot: os.Path =
    Option(System.getenv("MILL_WORKSPACE_ROOT")).map(os.Path(_)).getOrElse(os.pwd)

  private def annotationsJar: String = jarOf(classOf[com.fasterxml.jackson.annotation.JsonProperty])

  /** Compile the sources with javac, for Java 11, with all lint warnings. */
  def javac(sources: Seq[os.Path], classes: os.Path, classPath: Seq[String]): Seq[Diagnostic] = {
    val compiler = ToolProvider.getSystemJavaCompiler
    val collector = DiagnosticCollector[JavaFileObject]()
    val fileManager = compiler.getStandardFileManager(collector, null, null)
    val units = fileManager.getJavaFileObjectsFromFiles(sources.map(_.toIO).asJava)
    val options = Seq(
      "--release",
      "11",
      "-Xlint:all",
      "-encoding",
      "UTF-8",
      "-d",
      classes.toString,
      "-cp",
      classPath.mkString(java.io.File.pathSeparator),
    )
    compiler.getTask(null, fileManager, collector, options.asJava, null, units).call()
    fileManager.close()
    collector.getDiagnostics.asScala.toSeq.flatMap { d =>
      Option(d.getSource).map { source =>
        val path = os.Path(source.toUri.getPath)
        Diagnostic(
          path.segments.toSeq.reverse(1),
          d.getKind == javax.tools.Diagnostic.Kind.ERROR,
          s"${path.last}:${d.getLineNumber}: ${d.getMessage(null)}",
        )
      }
    }
  }

  final case class Diagnostic(pkg: String, error: Boolean, message: String)

  final case class Compiled(loader: ClassLoader, diagnostics: Map[String, Seq[Diagnostic]])

  /** What became of one document: its dump if it loaded, the error if Jackson rejected it, or the
    * crash if something else went wrong.
    */
  final case class Outcome(dump: Option[String], error: Option[String], crash: Option[String])

  /** One version of Jackson, with a default mapper. */
  trait Jackson {
    def name: String

    /** A suffix for the names of programs that use this version. */
    def suffix: String

    /** The jars that a program needs for this version. */
    def classPath: Seq[String]

    /** A program written for Jackson 2, changed to use this version. */
    def adapt(program: String): String

    def read(cls: Class[?], json: String): AnyRef

    def write(value: AnyRef): String

    /** Whether Jackson rejected the input, rather than crashed on it. */
    protected def rejected(e: Throwable): Boolean

    def load(cls: Class[?], json: String): Outcome =
      try Outcome(Some(write(read(cls, json))), None, None)
      catch {
        case e: Throwable if rejected(e) && !crashed(e) => Outcome(None, Some(e.getMessage), None)
        case e: Throwable =>
          val trace = java.io.StringWriter()
          e.printStackTrace(java.io.PrintWriter(trace))
          Outcome(None, None, Some(trace.toString))
      }
  }

  /** Whether one of the causes is a bug in the generated code rather than bad input. */
  private def crashed(e: Throwable): Boolean =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).exists {
      case _: NullPointerException | _: ClassCastException | _: IllegalStateException |
          _: UnsupportedOperationException | _: IndexOutOfBoundsException | _: Error =>
        true
      case _ => false
    }

  val jacksons: Seq[Jackson] = Seq(
    new Jackson {
      private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
      val name = "Jackson 2"
      val suffix = "2"
      def classPath: Seq[String] = Seq(
        jarOf(classOf[com.fasterxml.jackson.databind.ObjectMapper]),
        jarOf(classOf[com.fasterxml.jackson.core.JsonParser]),
      )
      def adapt(program: String): String = program
      def read(cls: Class[?], json: String): AnyRef =
        mapper.readValue(json, cls).asInstanceOf[AnyRef]
      def write(value: AnyRef): String = mapper.writeValueAsString(value)
      protected def rejected(e: Throwable): Boolean =
        e.isInstanceOf[com.fasterxml.jackson.core.JacksonException]
    },
    new Jackson {
      private val mapper = tools.jackson.databind.ObjectMapper()
      val name = "Jackson 3"
      val suffix = "3"
      def classPath: Seq[String] = Seq(
        jarOf(classOf[tools.jackson.databind.ObjectMapper]),
        jarOf(classOf[tools.jackson.core.JsonParser]),
      )
      def adapt(program: String): String =
        program.replace("com.fasterxml.jackson.databind.", "tools.jackson.databind.")
      def read(cls: Class[?], json: String): AnyRef =
        mapper.readValue(json, cls).asInstanceOf[AnyRef]
      def write(value: AnyRef): String = mapper.writeValueAsString(value)
      protected def rejected(e: Throwable): Boolean =
        e.isInstanceOf[tools.jackson.core.JacksonException]
    },
  )

  val trees: tools.jackson.databind.ObjectMapper = tools.jackson.databind.ObjectMapper()

  /** Whether two JSON texts have the same content, with numbers compared by value, as `1` and `1.0`
    * are the same number.
    */
  def sameJson(a: String, b: String): Boolean = {
    import tools.jackson.databind.JsonNode
    def same(x: JsonNode, y: JsonNode): Boolean =
      if x.isNumber && y.isNumber then x.decimalValue.compareTo(y.decimalValue) == 0
      else if x.isObject && y.isObject then
        x.propertyNames.asScala.toSet == y.propertyNames.asScala.toSet &&
        x.propertyNames.asScala.forall(k => same(x.get(k), y.get(k)))
      else if x.isArray && y.isArray then
        x.size == y.size && (0 until x.size).forall(i => same(x.get(i), y.get(i)))
      else x == y
    same(trees.readTree(a), trees.readTree(b))
  }

  /** The jar or directory with a class, for a class path. */
  def jarOf(cls: Class[?]): String =
    java.nio.file.Path.of(cls.getProtectionDomain.getCodeSource.getLocation.toURI).toString

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
      |      shift:
      |        range: Shift
      |  Note:
      |    extra_slots:
      |      allowed: true
      |    attributes:
      |      text:
      |  Memo:
      |    is_a: Note
      |enums:
      |  Shift:
      |    permissible_values:
      |      day:
      |      night shift:
      |""".stripMargin

  /** Checks the classes of [[behaviourSchema]] from Java code, written for Jackson 2. */
  private val behaviourChecks: String =
    """import com.fasterxml.jackson.databind.ObjectMapper;
      |import java.time.LocalDate;
      |import zoo.*;
      |
      |public class Behaviour {
      |    static final ObjectMapper M = new ObjectMapper();
      |    static final boolean STRICT = false;
      |
      |    static void check(boolean condition, Object what) {
      |        if (!condition) {
      |            throw new AssertionError(what);
      |        }
      |    }
      |
      |    static void rejects(String json, Class<?> cls) {
      |        try {
      |            M.readValue(json, cls);
      |        } catch (Exception e) {
      |            return;
      |        }
      |        throw new AssertionError("loaded " + json);
      |    }
      |
      |    public static void main(String[] args) throws Exception {
      |        // Objects made in code are written and read with the JSON keys
      |        Keeper keeper = new Keeper();
      |        keeper.setId("k1");
      |        keeper.setMySlot("x");
      |        keeper.setSince(LocalDate.of(2020, 1, 2));
      |        keeper.setShift(Shift.NIGHT_SHIFT);
      |        String json = M.writeValueAsString(keeper);
      |        check(json.equals("{\"id\":\"k1\",\"my-slot\":\"x\",\"shift\":\"night shift\",\"since\":\"2020-01-02\"}"), json);
      |        check(M.readValue(json, Keeper.class).equals(keeper), "equals");
      |        check(M.readValue(json, Keeper.class).hashCode() == keeper.hashCode(), "hashCode");
      |        check(Shift.fromValue("night shift") == Shift.NIGHT_SHIFT, "fromValue");
      |        check(Shift.DAY.getValue().equals("day"), "getValue");
      |
      |        // A designated class writes its type, and a union picks the class from it
      |        Lion lion = new Lion();
      |        lion.setName("Leo");
      |        json = M.writeValueAsString(lion);
      |        check(json.equals("{\"kind\":\"Lion\",\"name\":\"Leo\"}"), json);
      |        Zoo zoo = M.readValue("{\"animals\":[{\"kind\":\"Parrot\",\"name\":\"Polly\"}]}", Zoo.class);
      |        check(zoo.getAnimals().get(0) instanceof Parrot, zoo);
      |        rejects("{\"animals\":[{\"kind\":\"Animal\",\"name\":\"A\"}]}", Zoo.class);
      |
      |        // Dict keys go into the objects, and out again
      |        zoo = M.readValue("{\"keepers\":{\"k1\":{\"my-slot\":\"x\"},\"id\":{}}}", Zoo.class);
      |        check(zoo.getKeepers().get("k1").getId().equals("k1"), zoo);
      |        json = M.writeValueAsString(zoo);
      |        check(json.equals("{\"keepers\":{\"k1\":{\"my-slot\":\"x\"},\"id\":{}}}"), json);
      |        rejects("{\"keepers\":{\"k1\":{\"id\":\"k2\"}}}", Zoo.class);
      |
      |        // Required slots are required, and enums, dates and keys are checked
      |        rejects("{\"animals\":[{\"kind\":\"Lion\"}]}", Zoo.class);
      |        rejects("{\"keepers\":{\"k1\":{\"since\":\"yesterday\"}}}", Zoo.class);
      |        rejects("{\"keepers\":{\"k1\":{\"shift\":0}}}", Zoo.class);
      |        rejects("{\"colour\":\"red\"}", Zoo.class);
      |
      |        // Only strict classes don't convert values to the type of the slot
      |        String converted = "{\"animals\":[{\"kind\":\"Lion\",\"name\":1}]}";
      |        if (STRICT) {
      |            rejects(converted, Zoo.class);
      |            rejects("{\"animals\":[{\"kind\":\"Lion\",\"name\":null}]}", Zoo.class);
      |        } else {
      |            zoo = M.readValue(converted, Zoo.class);
      |            check(zoo.getAnimals().get(0).getName().equals("1"), zoo);
      |        }
      |
      |        // A class whose extra_slots allows them keeps other properties, but not its subclass
      |        Note note = M.readValue("{\"text\":\"t\",\"colour\":\"red\"}", Note.class);
      |        check(note.getExtraProperties().get("colour").equals("red"), note);
      |        json = M.writeValueAsString(note);
      |        check(json.equals("{\"text\":\"t\",\"colour\":\"red\"}"), json);
      |        rejects("{\"text\":\"t\",\"colour\":\"red\"}", Memo.class);
      |    }
      |}
      |""".stripMargin
}

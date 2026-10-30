package eu.neverblink.linkml.agents

import eu.neverblink.linkml.agents.Yaml.*
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaValidator, SchemaView}
import eu.neverblink.linkml.validation.MissingRecommendedSlot
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.{Node, Tag}

/** Checks the agent skills are well-formed and that every schema example in them still validates. A
  * documented schema that does not validate teaches an agent to write broken schemas.
  */
class SkillSpec extends AnyWordSpec, Matchers {
  import SkillSpec.*

  private val repo: os.Path =
    Option(System.getenv("MILL_WORKSPACE_ROOT")).map(os.Path(_)).getOrElse(os.pwd)
  private val agents = repo / ".agents"
  private val skillDirs =
    os.list(agents / "skills").filter(dir => os.isFile(dir / "SKILL.md")).sorted

  /** The Markdown files of a skill. Nested reference files are not checked. */
  private def docs(skillDir: os.Path) = os.list(skillDir).filter(_.ext == "md").sorted

  "The generated skill files" should {
    for (name, content) <- SkillFiles.generate(repo) do
      s"be up to date: $name" in {
        withClue("Regenerate with ./mill agents.generate:") {
          os.read(SkillFiles.skillDir(repo) / name) shouldBe content
        }
      }
  }

  "The agent skills" should {
    "exist" in {
      skillDirs should not be empty
    }

    "declare the required CLI version once, and repeat it consistently" in {
      // The README restates the requirement for a human reader, so it drifts with the skill.
      val texts = (skillDirs.flatMap(docs) :+ (agents / "README.md")).map(p => p -> os.read(p))
      val declared = texts.flatMap((p, t) => requirement.findFirstMatchIn(t).map(p -> _.group(1)))
      withClue("`Requirement: **X.Y.Z or newer**` must appear once:") {
        declared.map(_._1.relativeTo(repo)) should have size 1
      }
      val minimum = declared.head._2
      for (path, text) <- texts do
        withClue(s"${path.relativeTo(repo)}:") {
          restatement.findAllMatchIn(text).map(_.group(1)).toSet.foreach(_ shouldBe minimum)
          // Install commands should not name a version at all: `mise use --pin` records the newest
          // release. One that creeps back in must at least not be below the minimum.
          installPin.findAllMatchIn(text).map(_.group(1)).foreach { pin =>
            assert(versionOrdering.gteq(version(pin), version(minimum)), s"v$pin < $minimum")
          }
        }
    }

    // A broken template fails only once a user copies it into their own repository.
    for
      dir <- skillDirs.map(_ / "assets") if os.isDir(dir)
      asset <- os.list(dir) if asset.ext == "yml"
    do
      s"have a valid CI template ${asset.relativeTo(agents)}" in {
        Yaml.parse(os.read(asset)) shouldBe a[Right[?, ?]]
      }
  }

  for skillDir <- skillDirs do
    s"Skill '${skillDir.last}'" should {
      val text = os.read(skillDir / "SKILL.md")

      "have frontmatter within the Agent Skills spec" in {
        val (front, _) = splitFrontmatter(text).getOrElse(fail("no YAML frontmatter"))
        front.map(_._1).toSet -- allowedFrontmatter shouldBe empty
        val name = front.toMap.get("name").flatMap(_.text)
        name shouldBe Some(skillDir.last)
        name.get should fullyMatch regex "[a-z0-9]+(-[a-z0-9]+)*"
        // Without a description the skill never triggers.
        val description = front.toMap.get("description").flatMap(_.text).getOrElse("")
        description should not be empty
        description.codePointCount(0, description.length) should be <= 1024
      }

      "not use Claude-Code-only syntax, which Codex silently ignores" in {
        val body = splitFrontmatter(text).fold(text)(_._2)
        for (pattern, label) <- claudeOnly do
          withClue(s"$label:")(pattern.findFirstIn(body) shouldBe None)
        body.count(_ == '\n') should be <= 500
      }

      for md <- docs(skillDir) do
        val mdText = os.read(md)

        s"have working relative links in ${md.last}" in {
          for
            m <- link.findAllMatchIn(mdText)
            target = m.group(1).trim
            if !Seq("http", "mailto:", "/", "#").exists(target.startsWith)
          do
            assert(
              os.exists(md / os.up / os.RelPath(target)),
              s"links to $target, which does not exist",
            )
        }

        for (block, outcome) <- examples(mdText) do
          s"have a valid schema example in ${md.last}, block $block" in {
            val doc = outcome.fold(e => fail(s"not valid YAML: $e"), identity)
            val issues = SchemaView.loadSchemaViewFromString(ujson.write(doc)) match {
              case Right(sv) => SchemaValidator(using sv).lintProblems
              case Left(fatal) => fatal
            }
            // Like `validate --strict`: warnings fail too. Warnings about elements without a
            // description are skipped, as they are noise for a fragment.
            withClue(ujson.write(doc, indent = 2)) {
              issues.filter {
                case _: MissingRecommendedSlot => false
                case _ => true
              }.map(_.infer()).map(i =>
                s"${i.severity}: ${SchemaIssues.description(i)}",
              ) shouldBe empty
            }
          }
    }
}

object SkillSpec {
  // https://agentskills.io/specification - anything else is rejected by the claude.ai Skills API
  // and is meaningless to Codex.
  private val allowedFrontmatter =
    Set("name", "description", "license", "compatibility", "metadata", "allowed-tools")

  private val claudeOnly = Seq(
    raw"\$$ARGUMENTS\b".r -> "$ARGUMENTS",
    raw"\$$\{CLAUDE_[A-Z_]+\}".r -> "${CLAUDE_*}",
    raw"(?m)^\s*```!".r -> "```! dynamic block",
    raw"(?<!`)!`[^`\n]+`".r -> "!`cmd` injection",
  )

  private val link = raw"\]\(([^)#:]+)\)".r

  // `Requirement: **0.17.1 or newer**` is the one declaration, restated elsewhere as
  // `0.17.1 or newer`.
  private val requirement = raw"Requirement: \*\*(\d+\.\d+\.\d+) or newer\*\*".r
  private val restatement = raw"(\d+\.\d+\.\d+) or newer".r
  private val installPin = raw"(?:ohi|linkml-scala)@v(\d+\.\d+\.\d+)".r

  private def version(s: String) = s.split('.').map(_.toInt).toSeq

  private val versionOrdering = scala.math.Ordering.Implicits.seqOrdering[Seq, Int]

  private def splitFrontmatter(text: String): Option[(Seq[(String, Node)], String)] =
    if !text.startsWith("---\n") then None
    else
      val end = text.indexOf("\n---\n", 3)
      Option.when(end >= 0)(
        (Yaml.load(text.substring(4, end)).toSeq.flatMap(_.entries), text.substring(end + 5)),
      )

  private val envelope = ujson.Obj(
    "id" -> "https://example.org/skill-check",
    "name" -> "skill_check",
    "prefixes" -> ujson.Obj(
      "ex" -> "https://example.org/",
      "linkml" -> "https://w3id.org/linkml/",
      "schema" -> "http://schema.org/",
      "foaf" -> "http://xmlns.com/foaf/0.1/",
      "dcterms" -> "http://purl.org/dc/terms/",
    ),
    "default_prefix" -> "ex",
    "imports" -> ujson.Arr("linkml:types"),
  )

  // Classes the docs reference in passing without defining them in the same block
  private val stubs = Seq(
    "Person" -> ujson.Obj("attributes" -> ujson.Obj("name" -> ujson.Obj("range" -> "string"))),
    "Agent" -> ujson.Obj("attributes" -> ujson.Obj("agent_id" -> ujson.Obj("range" -> "string"))),
  )

  /** The ```yaml blocks of a document that define classes, by their 1-based number among all
    * blocks, each wrapped into a whole schema, or the error if it does not parse.
    *
    * Blocks are usually fragments. Top-level `slots:`, `settings:` and `prefixes:` accumulate
    * across a file, because later fragments refer back to them; `classes:` and `enums:` do not,
    * because consecutive blocks are often alternative illustrations of the same thing rather than
    * one growing schema.
    */
  private def examples(text: String): Seq[(Int, Either[String, ujson.Obj])] = {
    val carried = ujson.Obj()
    raw"(?s)```yaml\n(.*?)```".r.findAllMatchIn(text).map(_.group(1)).zipWithIndex.toSeq.flatMap {
      (block, index) =>
        Yaml.parse(block) match {
          case Left(error) => Some(index + 1 -> Left(error))
          case Right(Some(node: Node.MappingNode)) =>
            val fragment = json(node).obj
            for key <- Seq("slots", "settings"); value <- fragment.get(key).flatMap(_.objOpt) do
              carried(key) = merge(carried.value.getOrElse(key, ujson.Obj()), value)
            for value <- fragment.get("prefixes").flatMap(_.objOpt) do
              carried("prefixes") = merge(envelope("prefixes"), value)
            wrap(fragment, carried).map(doc => index + 1 -> Right(doc))
          case _ => None
        }
    }
  }

  private def wrap(fragment: ujson.Obj, carried: ujson.Obj): Option[ujson.Obj] = {
    val doc = ujson.copy(envelope).obj
    for (key, value) <- carried.value do doc(key) = ujson.copy(value)
    for (key, value) <- fragment.value do
      key match {
        // `linkml:` imports resolve against the bundled metamodel, so keep them. Relative imports
        // point at sibling files that do not exist here, so drop those.
        case "imports" =>
          val linkml = value.arrOpt.toSeq.flatten.collect {
            case ujson.Str(i) if i.startsWith("linkml:") => ujson.Str(i)
          }
          doc("imports") = ujson.Arr.from((doc("imports").arr ++ linkml).distinct)
        case "prefixes" => doc(key) = merge(doc(key), value.obj)
        case "slots" | "settings" if value.objOpt.isDefined =>
          doc(key) = merge(doc.value.getOrElse(key, ujson.Obj()), value.obj)
        case _ => doc(key) = value
      }

    doc.value.get("classes").flatMap(_.objOpt).filter(_.nonEmpty).map { classes =>
      val rendered = ujson.write(doc)
      for (name, body) <- stubs if raw"\b$name\b".r.findFirstIn(rendered).nonEmpty do
        classes.getOrElseUpdate(name, ujson.copy(body))
      // `validate --strict` warns without a tree root, which is noise for a fragment.
      if !classes.values.exists(c => c.objOpt.flatMap(_.get("tree_root")).exists(truthy)) then
        val (first, body) = classes.head
        classes(first) = ujson.Obj.from(body.objOpt.toSeq.flatten :+ ("tree_root" -> ujson.True))
      doc
    }
  }

  private def merge(base: ujson.Value, overrides: collection.Map[String, ujson.Value]) =
    ujson.Obj.from(base.obj.toSeq ++ overrides)

  private def truthy(v: ujson.Value) = v match {
    case ujson.Null | ujson.False => false
    case ujson.Str(s) => s.nonEmpty
    case _ => true
  }

  private def json(node: Node): ujson.Value = node match {
    case s: Node.ScalarNode =>
      s.tag match {
        case Tag.nullTag => ujson.Null
        case Tag.boolean => ujson.Bool(s.value.toBoolean)
        case Tag.int | Tag.float => s.value.toDoubleOption.fold(ujson.Str(s.value))(ujson.Num(_))
        case _ => ujson.Str(s.value)
      }
    case s: Node.SequenceNode => ujson.Arr.from(s.nodes.map(json))
    case m: Node.MappingNode => ujson.Obj.from(m.entries.map((k, v) => k -> json(v)))
  }
}

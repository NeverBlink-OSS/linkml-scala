package eu.neverblink.linkml.validation

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.{Node, parseYaml}

import scala.io.Source
import scala.util.Using

/** Guards [[Codec]] against drifting from `model/issue-types.yaml`.
  *
  * The codec has to dispatch over every concrete issue class by hand, because `SchemaIssue` is not
  * sealed and the codec macro can therefore not enumerate its subtypes. Nothing in the compiler
  * notices when a new issue type is added to the schema and not to the codec - serializing a report
  * that contains one would only fail at runtime. This test is that missing check.
  *
  * JVM-only: it reads the schema and the codec source off disk.
  */
class CodecCoverageSpec extends AnyWordSpec, Matchers {

  private val workspaceRoot: String =
    Option(System.getenv("MILL_WORKSPACE_ROOT")).getOrElse(System.getProperty("user.dir"))

  private def read(relativePath: String): String =
    Using.resource(Source.fromFile(s"$workspaceRoot/$relativePath"))(_.mkString)

  /** Concrete issue classes declared in the schema. Abstract ones have no `...Impl` to encode. */
  private def issueClassesInSchema: Set[String] = {
    val text = read("model/issue-types.yaml")
    val root = parseYaml(text).fold(err => fail(s"Cannot parse the schema: ${err.msg}"), identity)
    val classes = root match {
      case m: Node.MappingNode =>
        m.mappings.collectFirst {
          case (k: Node.ScalarNode, v: Node.MappingNode) if k.value == "classes" => v
        }
      case _ => None
    }
    classes.getOrElse(fail("The schema has no 'classes' mapping")).mappings.collect {
      case (k: Node.ScalarNode, body: Node.MappingNode) if !isAbstract(body) => k.value
    }.toSet
  }

  private def isAbstract(classBody: Node.MappingNode): Boolean =
    classBody.mappings.exists {
      case (k: Node.ScalarNode, v: Node.ScalarNode) => k.value == "abstract" && v.value == "true"
      case _ => false
    }

  /** Issue classes the codec dispatches on, read back out of its source. */
  private def issueClassesInCodec: Set[String] =
    """case i: (\w+)Impl =>""".r
      .findAllMatchIn(read("validation/src/eu/neverblink/linkml/validation/Codec.scala"))
      .map(_.group(1))
      .toSet

  "Codec" should {
    "dispatch on every concrete issue class in the schema" in {
      val inSchema = issueClassesInSchema
      val inCodec = issueClassesInCodec

      withClue("sanity check - the schema should declare some issue classes: ") {
        inSchema should not be empty
      }

      val missing = (inSchema -- inCodec).toSeq.sorted
      withClue(
        s"Codec.issueCodec is missing a case for: ${missing.mkString(", ")}. " +
          "Add `case i: <Name>Impl => <name>.encode(i, skipId)` and the matching derived codec. ",
      )(missing shouldBe empty)

      val stale = (inCodec -- inSchema).toSeq.sorted
      withClue(
        s"Codec.issueCodec dispatches on types that the schema no longer declares: " +
          s"${stale.mkString(", ")}. ",
      )(stale shouldBe empty)
    }
  }
}

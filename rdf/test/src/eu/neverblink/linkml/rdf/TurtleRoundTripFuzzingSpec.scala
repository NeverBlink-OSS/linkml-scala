package eu.neverblink.linkml.rdf

import eu.neverblink.linkml.rdf.io.Utf8ByteSink
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

/** Random triples through [[TurtleWriter]] and back through [[TurtleParser]]. */
class TurtleRoundTripFuzzingSpec extends AnyWordSpec, Matchers, ScalaCheckPropertyChecks {
  import TurtleRoundTripFuzzingSpec.*

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 500)

  /** Write with the given prefixes, and read back through a buffer of `bufferSize`. */
  private def roundTrip(
      prefixes: Seq[String],
      bufferSize: Int,
      write: RdfSink => Unit,
  ): Seq[Triple] = {
    val out = new ByteArrayOutputStream
    val sink = new Utf8ByteSink(out)
    val writer = new TurtleWriter(sink)
    prefixes.zipWithIndex.foreach((name, i) => writer.namespace(s"p$i", name))
    write(writer)
    writer.finish()
    sink.flush()

    val collected = new CollectingRdfSink
    TurtleParser.parse(new ByteArrayInputStream(out.toByteArray), collected, None, bufferSize)
    collected.triples
  }

  "TurtleParser" should {
    "read back exactly what TurtleWriter writes, through any buffer size" in {
      forAll(Gen.listOf(NTriplesRoundTripFuzzingSpec.triple), prefixes, Gen.choose(16, 256)) {
        (triples, prefixes, bufferSize) =>
          roundTrip(prefixes, bufferSize, RdfUtils.writeAll(_, triples)) shouldBe triples
      }
    }

    "read back nested blank nodes and collections, up to their labels" in {
      forAll(Gen.listOf(statement), prefixes, Gen.choose(16, 256)) {
        (statements, prefixes, bufferSize) =>
          val write = (sink: RdfSink) => statements.foreach(_(sink))
          val pushed = new CollectingRdfSink
          write(pushed)
          RdfUtils.sameUpToBlankNodes(
            roundTrip(prefixes, bufferSize, write),
            pushed.triples,
          ) shouldBe
            true
      }
    }
  }
}

object TurtleRoundTripFuzzingSpec {
  import NTriplesRoundTripFuzzingSpec.triple

  /** Namespaces that some of the generated IRIs start with, so that they get prefixed names. */
  private val prefixes: Gen[Seq[String]] =
    Gen.listOf(Gen.oneOf("http:", "http://", "urn:", "urn:a", "a+b.c-d:")).map(_.distinct)

  private var inlineLabels = 0

  /** Keep the generated labels clear of the `l1`, `l2`, ... that [[RdfSink.list]] uses, and of the
    * inline blank nodes' labels, as [[RdfUtils.sameUpToBlankNodes]] would take them for the same
    * node.
    */
  private def labeled[N <: Node](node: N): N = node match {
    case BlankNode(id) => BlankNode("b" + id).asInstanceOf[N]
    case other => other
  }

  private val obj = triple.map(t => labeled(t.obj))

  /** Up to a few of `gen`, so that nesting does not blow up the size. */
  private def few[T](gen: Gen[T]): Gen[List[T]] = Gen.choose(0, 4).flatMap(Gen.listOfN(_, gen))

  /** A subject with its triples, some with nested blank nodes or collections as objects. */
  private val statement: Gen[RdfSink => Unit] = {
    def nested(depth: Int): Gen[RdfSink => Resource => Unit] = for
      pred <- triple.map(_.pred)
      write <- Gen.frequency(
        4 -> obj.map(o => (s: RdfSink) => (subj: Resource) => s.triple(subj, pred, o)),
        (if depth > 0 then 1 else 0) -> few(Gen.lzy(nested(depth - 1))).map {
          inner => (s: RdfSink) => (subj: Resource) =>
            {
              inlineLabels += 1
              val node = InlineBlankNode(s"inline#$inlineLabels")
              s.triple(subj, pred, node)
              inner.foreach(_(s)(node))
            }
        },
        1 -> few(obj).map { values => (s: RdfSink) => (subj: Resource) =>
          s.list(subj, pred, values)
        },
      )
    yield write

    for
      subj <- triple.map(t => labeled(t.subj))
      objects <- few(nested(3))
    yield (sink: RdfSink) => objects.foreach(_(sink)(subj))
  }
}

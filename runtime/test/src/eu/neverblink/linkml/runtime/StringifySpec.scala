package eu.neverblink.linkml.runtime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StringifySpec extends AnyWordSpec, Matchers {

  "stringify" should {
    "render a string as itself" in {
      stringify("hello") shouldBe "hello"
    }

    "render numbers and booleans" in {
      stringify(42) shouldBe "42"
      stringify(1.5f) shouldBe "1.5"
      stringify(1.5d) shouldBe "1.5"
      stringify(BigDecimal("1.50")) shouldBe "1.50"
      stringify(true) shouldBe "true"
    }

    "render dates and times as written" in {
      stringify(LinkmlDate("2026-08-06")) shouldBe "2026-08-06"
      stringify(LinkmlTime("12:34:56")) shouldBe "12:34:56"
      stringify(LinkmlDateTime("2026-08-06T12:34:56")) shouldBe "2026-08-06T12:34:56"
    }

    "render URIs and CURIEs without resolving prefixes" in {
      stringify(Uri("https://example.org/thing")) shouldBe "https://example.org/thing"
      stringify(Curie("ex:thing")) shouldBe "ex:thing"
    }

    "render a UriOrCurie through the sealed trait" in {
      val either: UriOrCurie = UriOrCurie("ex:thing")
      stringify(either) shouldBe "ex:thing"
    }

    "render a LinkmlAny as its encoded content" in {
      stringify(LinkmlAny("{a: 1}")) shouldBe "{a: 1}"
    }

    "render a reference as the identifier it holds" in {
      stringify(Reference[String]("some-id")) shouldBe "some-id"
    }

    "join a multivalued slot with ', '" in {
      stringify(Seq("a", "b", "c")) shouldBe "a, b, c"
    }

    "render an empty multivalued slot as an empty string" in {
      stringify(Seq.empty[String]) shouldBe ""
    }

    "join a multivalued slot of non-strings" in {
      stringify(Seq(Curie("ex:a"), Curie("ex:b"))) shouldBe "ex:a, ex:b"
      stringify(Seq(Reference[String]("1"), Reference[String]("2"))) shouldBe "1, 2"
      stringify(Seq(1, 2, 3)) shouldBe "1, 2, 3"
    }

    "use a user-supplied instance for an unknown type" in {
      final case class External(parts: Seq[String])
      given Stringify[External] = _.parts.mkString("/")

      stringify(External(Seq("a", "b"))) shouldBe "a/b"
      stringify(Seq(External(Seq("a")), External(Seq("b")))) shouldBe "a, b"
    }

    "cover subtypes through one instance, since Stringify is contravariant" in {
      // A `Uri` is served by the `UriOrCurie` instance, with no instance of its own
      stringify(Uri("urn:x")) shouldBe "urn:x"
      stringify(Seq(Uri("urn:x"), Curie("ex:y"))) shouldBe "urn:x, ex:y"
    }
  }

  "Stringify.derived" should {
    "read the LinkML text from @named, not the case name" in {
      stringify(Sealed.Renamed) shouldBe "renamed in the schema"
      Sealed.Renamed.toString shouldBe "Renamed"
    }

    "fall back to the case name when there is no @named" in {
      stringify(Sealed.Plain) shouldBe "Plain"
    }

    "apply to a singleton case type, and to a sequence of cases" in {
      // `Sealed.Plain.type`, not `Sealed` – contravariance makes this resolve
      val one: Sealed.Plain.type = Sealed.Plain
      stringify(one) shouldBe "Plain"
      stringify(Seq[Sealed](Sealed.Plain, Sealed.Renamed)) shouldBe
        "Plain, renamed in the schema"
    }
  }
}

/** Shaped like the enums [[eu.neverblink.linkml.generator.scala.ScalaGenerator]] emits. */
sealed abstract class Sealed derives Stringify

object Sealed {
  case object Plain extends Sealed
  @named("renamed in the schema") case object Renamed extends Sealed
}

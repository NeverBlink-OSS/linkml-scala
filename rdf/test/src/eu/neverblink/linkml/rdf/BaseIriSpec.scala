package eu.neverblink.linkml.rdf

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BaseIriSpec extends AnyWordSpec, Matchers {

  /** The examples of RFC 3986 section 5.4, for the base `http://a/b/c/d;p?q`. */
  private val rfcExamples = Seq(
    "g:h" -> "g:h",
    "g" -> "http://a/b/c/g",
    "./g" -> "http://a/b/c/g",
    "g/" -> "http://a/b/c/g/",
    "/g" -> "http://a/g",
    "//g" -> "http://g",
    "?y" -> "http://a/b/c/d;p?y",
    "g?y" -> "http://a/b/c/g?y",
    "#s" -> "http://a/b/c/d;p?q#s",
    "g#s" -> "http://a/b/c/g#s",
    "g?y#s" -> "http://a/b/c/g?y#s",
    ";x" -> "http://a/b/c/;x",
    "g;x" -> "http://a/b/c/g;x",
    "g;x?y#s" -> "http://a/b/c/g;x?y#s",
    "" -> "http://a/b/c/d;p?q",
    "." -> "http://a/b/c/",
    "./" -> "http://a/b/c/",
    ".." -> "http://a/b/",
    "../" -> "http://a/b/",
    "../g" -> "http://a/b/g",
    "../.." -> "http://a/",
    "../../" -> "http://a/",
    "../../g" -> "http://a/g",
    // Abnormal examples
    "../../../g" -> "http://a/g",
    "../../../../g" -> "http://a/g",
    "/./g" -> "http://a/g",
    "/../g" -> "http://a/g",
    "g." -> "http://a/b/c/g.",
    ".g" -> "http://a/b/c/.g",
    "g.." -> "http://a/b/c/g..",
    "..g" -> "http://a/b/c/..g",
    "./../g" -> "http://a/b/g",
    "./g/." -> "http://a/b/c/g/",
    "g/./h" -> "http://a/b/c/g/h",
    "g/../h" -> "http://a/b/c/h",
    "g;x=1/./y" -> "http://a/b/c/g;x=1/y",
    "g;x=1/../y" -> "http://a/b/c/y",
    "g?y/./x" -> "http://a/b/c/g?y/./x",
    "g?y/../x" -> "http://a/b/c/g?y/../x",
    "g#s/./x" -> "http://a/b/c/g#s/./x",
    "g#s/../x" -> "http://a/b/c/g#s/../x",
    "http:g" -> "http:g",
  )

  "BaseIri" should {
    val base = BaseIri("http://a/b/c/d;p?q")
    for (ref, expected) <- rfcExamples do
      s"resolve '$ref' as RFC 3986 does" in {
        base.resolve(ref) shouldBe expected
      }

    "drop the base's fragment" in {
      BaseIri("http://a/b#f").resolve("") shouldBe "http://a/b"
    }
    "resolve against a base with no path or no authority" in {
      BaseIri("http://a").resolve("g") shouldBe "http://a/g"
      BaseIri("urn:x:y").resolve("z") shouldBe "urn:z"
      BaseIri("file:///x/y").resolve("z") shouldBe "file:///x/z"
    }
    "refuse a base that is not absolute" in {
      intercept[IllegalArgumentException](BaseIri("/a/b"))
    }
  }
}

package eu.neverblink.ohi.model

import eu.neverblink.linkml.runtime.{Uri, stringify}
import eu.neverblink.ohi.model.generated.lock.*
import eu.neverblink.ohi.model.generated.lockerror.{LockErrorCode, LockOperation}
import java.nio.file.{Files, Path}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LockJsonSpec extends AnyWordSpec, Matchers {
  private def example(revision: Int = 1): ujson.Value = {
    val data = ujson.read(
      Files.readString(
        Path.of(sys.env("MILL_WORKSPACE_ROOT"), "ohi", "examples", "selection.lock.json"),
      ),
    )
    data("format_version") = revision
    if revision == 2 then {
      data("files")(0)("sha256") =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      data("files")(1)("sha256") =
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
    data
  }

  private def decode(value: ujson.Value): Lock = LockJson.decode(ujson.write(value))

  private def assertError(failure: LockException, code: String, operation: String): Unit = {
    stringify(failure.error.code) shouldBe code
    stringify(failure.error.operation) shouldBe operation
    failure.error.path shouldBe None
    failure.error.message should not be empty
  }

  "LockJson.decode" should {
    for revision <- Seq(1, 2) do
      s"preserve revision $revision selections, ordered files, URI spelling and hashes" in {
        val lock = decode(example(revision))
        lock.formatVersion shouldBe revision
        lock.selections.map(s => (s.baseUrl.original, s.url.original)) shouldBe Seq(
          (
            "https://Hub.example/releases/z/2/?v=%2F#chosen",
            "https://Files.example/Z%2F.ttl?download=1#file",
          ),
        )
        lock.files.map(
          _.sha256,
        ) shouldBe (if revision == 1 then Seq(None, None)
                    else
                      Seq(
                        Some("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                        Some("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                      ))
        lock.files.map(f => (f.baseUrl.original, f.url.original, f.format)) shouldBe Seq(
          (
            "https://Hub.example/releases/z/2/?v=%2F#chosen",
            "https://Files.example/Z%2F.ttl?download=1#file",
            Format.OwlTurtle,
          ),
          ("https://Hub.example/releases/a/1/", "https://files.example/a.yaml", Format.LinkmlYaml),
        )
      }

    for (text, behavior) <- Seq(
        "{" -> "malformed JSON",
        "" -> "empty input",
        "null" -> "null root",
        "[]" -> "array root",
        "\uFEFF{}" -> "leading BOM",
      )
    do
      s"reject $behavior with a decode error" in {
        assertError(intercept[LockException](LockJson.decode(text)), "INVALID_LOCK", "DECODE")
      }

    "retain the native malformed-JSON cause" in {
      intercept[LockException](LockJson.decode("{")).getCause should not be null
    }

    for field <- Seq("selections", "files") do {
      s"reject an omitted $field collection" in {
        val data = example()
        data.obj.remove(field)
        assertError(intercept[LockException](decode(data)), "INVALID_LOCK", "DECODE")
      }
    }

    for (label, hash) <- Seq(
        "a missing hash" -> None,
        "a null hash" -> Some(ujson.Null),
      )
    do
      s"reject revision 2 with $label" in {
        val data = example(2)
        data("files")(1).obj.remove("sha256")
        hash.foreach(value => data("files")(1)("sha256") = value)
        assertError(intercept[LockException](decode(data)), "INVALID_LOCK", "DECODE")
      }

    for hash <- Seq(
        ujson.Str("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
      )
    do
      s"reject revision 1 with a supplied hash $hash" in {
        val data = example()
        data("files")(0)("sha256") = hash
        assertError(intercept[LockException](decode(data)), "INVALID_LOCK", "DECODE")
      }

    "reject unknown artifact formats" in {
      val data = example()
      data("files")(0)("format") = "future-format"
      assertError(intercept[LockException](decode(data)), "INVALID_LOCK", "DECODE")
    }

    for (format, expected) <- Seq(
        "owl-rdfxml" -> Format.OwlRdfxml,
        "rdfs-turtle" -> Format.RdfsTurtle,
        "ossie-yaml" -> Format.OssieYaml,
      )
    do
      s"preserve format $format" in {
        val data = example()
        data("files")(0)("format") = format
        decode(data).files.head.format shouldBe expected
      }

    for revision <- Seq("3", "99999999999") do
      s"report unsupported revision $revision before missing collections" in {
        assertError(
          intercept[LockException](LockJson.decode(s"""{"format_version":$revision}""")),
          "UNSUPPORTED_VERSION",
          "DECODE",
        )
      }

    for revision <- Seq("true", "1.5", "\"1\"", "null") do
      s"reject noninteger revision $revision without rounding it to one" in {
        assertError(
          intercept[LockException](
            LockJson.decode(s"""{"format_version":$revision,"selections":[],"files":[]}"""),
          ),
          "INVALID_LOCK",
          "DECODE",
        )
      }

    "reject an omitted revision" in {
      assertError(
        intercept[LockException](LockJson.decode("""{"selections":[],"files":[]}""")),
        "INVALID_LOCK",
        "DECODE",
      )
    }

    for field <- Seq("selections", "files") do
      s"reject a duplicate exact pair in $field" in {
        val data = example()
        data(field).arr += data(field)(0)
        assertError(intercept[LockException](decode(data)), "INVALID_LOCK", "DECODE")
      }

    "reject duplicate file pairs with conflicting formats" in {
      val data = example()
      val conflicting = ujson.read(ujson.write(data("files")(0)))
      conflicting("format") = "owl-rdfxml"
      data("files").arr += conflicting
      assertError(intercept[LockException](decode(data)), "INVALID_LOCK", "DECODE")
    }

    "reject a direct selection missing its exact file pair" in {
      val data = example()
      data("files").arr.remove(0)
      assertError(intercept[LockException](decode(data)), "INVALID_LOCK", "DECODE")
    }

    "distinguish same URLs across publications without imposing hash consistency" in {
      val data = ujson.read("""{
        "format_version":2,
        "selections":[
          {"base_url":"urn:release:z","url":"urn:file:shared"},
          {"base_url":"urn:release:a","url":"urn:file:shared"}
        ],
        "files":[
          {"base_url":"urn:release:z","url":"urn:file:shared","format":"owl-turtle",
           "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
          {"base_url":"urn:release:a","url":"urn:file:shared","format":"owl-turtle",
           "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
        ]
      }""")
      val lock = decode(data)
      lock.selections.map(_.baseUrl.original) shouldBe Seq("urn:release:z", "urn:release:a")
      lock.files.map(_.baseUrl.original) shouldBe Seq("urn:release:z", "urn:release:a")
      ujson.read(LockJson.encode(lock))("selections").arr.map(_("base_url").str).toSeq shouldBe
        Seq("urn:release:z", "urn:release:a")
    }

    "distinguish files of one publication and URI-equivalent spellings" in {
      val lock = LockJson.decode("""{
        "format_version":1,"selections":[],
        "files":[
          {"base_url":"urn:release:z","url":"https://Example.org/a","format":"owl-turtle"},
          {"base_url":"urn:release:z","url":"https://example.org/a","format":"owl-turtle"},
          {"base_url":"urn:release:z","url":"https://example.org/b","format":"owl-turtle"}
        ]
      }""")
      lock.files.map(_.url.original) shouldBe Seq(
        "https://Example.org/a",
        "https://example.org/a",
        "https://example.org/b",
      )
    }
  }

  "LockJson.encode" should {
    for revision <- Seq(1, 2) do
      s"emit the independently specified revision $revision keys, values, formats and file order" in {
        val data = ujson.read(LockJson.encode(decode(example(revision))))
        data.obj.keySet shouldBe Set("format_version", "selections", "files")
        data("format_version").num shouldBe revision
        data("selections") shouldBe ujson.Arr(
          ujson.Obj(
            "base_url" -> "https://Hub.example/releases/z/2/?v=%2F#chosen",
            "url" -> "https://Files.example/Z%2F.ttl?download=1#file",
          ),
        )
        val expectedFiles = ujson.Arr(
          ujson.Obj(
            "base_url" -> "https://Hub.example/releases/z/2/?v=%2F#chosen",
            "url" -> "https://Files.example/Z%2F.ttl?download=1#file",
            "format" -> "owl-turtle",
          ),
          ujson.Obj(
            "base_url" -> "https://Hub.example/releases/a/1/",
            "url" -> "https://files.example/a.yaml",
            "format" -> "linkml-yaml",
          ),
        )
        if revision == 2 then {
          expectedFiles(0)("sha256") =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          expectedFiles(1)("sha256") =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        }
        data("files") shouldBe expectedFiles
      }

    for revision <- Seq(1, 2) do
      s"retain both required empty arrays in revision $revision" in {
        ujson.read(LockJson.encode(Lock(Seq.empty, revision, Seq.empty))) shouldBe
          ujson.Obj(
            "format_version" -> revision,
            "selections" -> ujson.Arr(),
            "files" -> ujson.Arr(),
          )
      }

    "classify unsupported typed revisions as encode errors" in {
      assertError(
        intercept[LockException](LockJson.encode(Lock(Seq.empty, 3, Seq.empty))),
        "UNSUPPORTED_VERSION",
        "ENCODE",
      )
    }

    for (revision, hash) <- Seq(
        1 -> Some("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        2 -> None,
      )
    do
      s"reject revision $revision with invalid typed hash $hash" in {
        val lock = Lock(
          Seq(LockedFile(Uri("urn:release:z"), Format.OwlTurtle, Uri("urn:file:z"), hash)),
          revision,
          Seq.empty,
        )
        assertError(intercept[LockException](LockJson.encode(lock)), "INVALID_LOCK", "ENCODE")
      }

    "reject incomplete records before returning encoded text" in {
      val lock =
        Lock(Seq.empty, 1, Seq(ArtifactSelectionImpl(Uri("urn:release:z"), Uri("urn:file:z"))))
      assertError(intercept[LockException](LockJson.encode(lock)), "INVALID_LOCK", "ENCODE")
    }
  }
}

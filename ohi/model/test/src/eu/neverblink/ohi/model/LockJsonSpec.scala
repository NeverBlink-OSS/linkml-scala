package eu.neverblink.ohi.model

import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.model.generated.lock.*
import eu.neverblink.ohi.model.generated.lockerror.{LockErrorCode, LockError, LockOperation}
import java.nio.file.{Files, Path}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LockJsonSpec extends AnyWordSpec, Matchers {
  private def example(): ujson.Value = ujson.read(
    Files.readString(
      Path.of(sys.env("MILL_WORKSPACE_ROOT"), "ohi", "examples", "selection.lock.json"),
    ),
  )

  private def decode(value: ujson.Value): Lock = LockJson.decode(ujson.write(value))

  private def assertError(failure: LockException, code: String, operation: String): Unit = {
    val data = ujson.read(LockErrorJson.encode(failure.error))
    data("code").str shouldBe code
    data("operation").str shouldBe operation
    data.obj.keySet shouldBe Set("code", "operation", "message")
    data("message").str should not be empty
  }

  "LockJson.decode" should {
    "preserve the supplied direct selection and dependency file order and URI spelling" in {
      val lock = decode(example())
      lock.formatVersion shouldBe 1
      lock.selections.map(s => (s.baseUrl.original, s.url.original)) shouldBe Seq(
        (
          "https://Hub.example/releases/z/2/?v=%2F#chosen",
          "https://Files.example/Z%2F.ttl?download=1#file",
        ),
      )
      lock.files.map(f => (f.baseUrl.original, f.url.original, f.format)) shouldBe Seq(
        (
          "https://Hub.example/releases/z/2/?v=%2F#chosen",
          "https://Files.example/Z%2F.ttl?download=1#file",
          Format.OwlTurtle,
        ),
        ("https://Hub.example/releases/a/1/", "https://files.example/a.yaml", Format.LinkmlYaml),
      )
    }

    "accept an empty lock with an integral decimal revision" in {
      val lock = LockJson.decode("""{"format_version":1.0,"selections":[],"files":[]}""")
      lock.formatVersion shouldBe 1
      lock.selections shouldBe empty
      lock.files shouldBe empty
    }

    for (text, behavior) <- Seq(
        "{" -> "malformed JSON",
        "" -> "empty input",
        "null" -> "null root",
        "[]" -> "array root",
        "{} false" -> "trailing JSON",
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
      s"reject a null $field collection" in {
        val data = example()
        data(field) = ujson.Null
        assertError(intercept[LockException](decode(data)), "INVALID_LOCK", "DECODE")
      }
    }

    for field <- Seq("root", "selections", "files") do
      s"reject unknown properties in $field" in {
        val data = example()
        val target = if field == "root" then data else data(field)(0)
        target("extra") = true
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

    for (field, value) <- Seq("base_url" -> "relative", "url" -> "https://example.org/a b") do
      s"validate the $field URI format" in {
        val data = example()
        data("files")(1)(field) = value
        assertError(intercept[LockException](decode(data)), "INVALID_LOCK", "DECODE")
      }

    for revision <- Seq("2.0", "1e400") do
      s"report unsupported integral revision $revision before missing collections" in {
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

    "distinguish same URLs across publications and preserve selection order" in {
      val data = ujson.read("""{
        "format_version":1,
        "selections":[
          {"base_url":"urn:release:z","url":"urn:file:shared"},
          {"base_url":"urn:release:a","url":"urn:file:shared"}
        ],
        "files":[
          {"base_url":"urn:release:z","url":"urn:file:shared","format":"owl-turtle"},
          {"base_url":"urn:release:a","url":"urn:file:shared","format":"owl-turtle"}
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
    "emit the independently specified keys, values, formats and file order" in {
      val data = ujson.read(LockJson.encode(decode(example())))
      data.obj.keySet shouldBe Set("format_version", "selections", "files")
      data("format_version").num shouldBe 1
      data("selections") shouldBe ujson.Arr(
        ujson.Obj(
          "base_url" -> "https://Hub.example/releases/z/2/?v=%2F#chosen",
          "url" -> "https://Files.example/Z%2F.ttl?download=1#file",
        ),
      )
      data("files") shouldBe ujson.Arr(
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
    }

    "retain both required empty arrays" in {
      ujson.read(LockJson.encode(Lock(Seq.empty, 1, Seq.empty))) shouldBe
        ujson.Obj("format_version" -> 1, "selections" -> ujson.Arr(), "files" -> ujson.Arr())
    }

    "classify unsupported typed revisions as encode errors" in {
      assertError(
        intercept[LockException](LockJson.encode(Lock(Seq.empty, 2, Seq.empty))),
        "UNSUPPORTED_VERSION",
        "ENCODE",
      )
    }

    "validate URI fields reachable through generated constructors" in {
      val lock = Lock(
        Seq(LockedFile(Uri("relative"), Format.OwlTurtle, Uri("urn:file:z"))),
        1,
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

  "LockErrorJson.encode" should {
    "emit the generated error values and omit absent text paths" in {
      ujson.read(
        LockErrorJson.encode(
          LockError(LockErrorCode.InvalidLock, "Correct the lock", LockOperation.Decode),
        ),
      ) shouldBe
        ujson.Obj(
          "code" -> "INVALID_LOCK",
          "operation" -> "DECODE",
          "message" -> "Correct the lock",
        )
    }

    "preserve caller path spelling without including a native cause" in {
      ujson.read(
        LockErrorJson.encode(
          LockError(
            LockErrorCode.StorageError,
            "Check the path",
            LockOperation.Write,
            Some("./locks/../chosen.json"),
          ),
        ),
      ) shouldBe
        ujson.Obj(
          "code" -> "STORAGE_ERROR",
          "operation" -> "WRITE",
          "message" -> "Check the path",
          "path" -> "./locks/../chosen.json",
        )
    }
  }
}

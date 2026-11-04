package eu.neverblink.linkml.ohipackages

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class OhiPackagesSpec extends AnyWordSpec, Matchers {

  private def withModel(files: (String, String)*)(test: (os.Path, os.Path) => Unit): Unit = {
    val dir = os.temp.dir()
    try {
      files.foreach((path, text) =>
        os.write(dir / "model" / os.RelPath(path), text, createFolders = true),
      )
      os.write(dir / "LICENSE", "license text")
      test(dir / "model", dir)
    } finally os.remove.all(dir)
  }

  private val common =
    """id: https://example.org/common
      |name: my_common
      |title: Common things
      |description: >-
      |  Shared slots
      |  and types.
      |imports:
      |  - linkml:types
      |""".stripMargin

  private val user =
    """id: https://example.org/user
      |name: user
      |imports:
      |- linkml:types
      |# the shared part
      |- ../common  # relative
      |classes:
      |  User:
      |    description: imports here are not touched
      |""".stripMargin

  "OhiPackages.write" should {
    "write one package per schema, with the schema, the license, a README and the manifest" in {
      withModel("common.yaml" -> common, "sub/user.yml" -> user) { (model, dir) =>
        val out = dir / "out"
        val packages = OhiPackages.write(model, dir / "LICENSE", out, "1.2.3", "abc123")
        packages.map(_.schema.name) shouldBe Seq("my-common", "user")

        val folder = out / "ontologies" / "ohi" / "my-common" / "1.2.3"
        os.list(folder).map(_.last).toSet shouldBe
          Set("common.yaml", "LICENSE", "README.md", "ohi-package.yaml")
        os.read(folder / "common.yaml") shouldBe common
        os.read(folder / "LICENSE") shouldBe "license text"
        os.read(folder / "ohi-package.yaml") shouldBe
          """namespace: ohi
            |name: my-common
            |version: "1.2.3"
            |language: linkml
            |syntax: yaml
            |roots: ["common.yaml"]
            |licenses: ["Apache-2.0"]
            |licenseFiles: [LICENSE]
            |dependencies: []
            |title: "Common things"
            |publisher: "NeverBlink"
            |description: "Shared slots and types."
            |keywords: ["linkml"]
            |""".stripMargin
      }
    }

    "turn an import of another schema into a dependency and point it to that package" in {
      withModel("common.yaml" -> common, "sub/user.yml" -> user) { (model, dir) =>
        val out = dir / "out"
        OhiPackages.write(model, dir / "LICENSE", out, "1.2.3", "abc123")

        val folder = out / "ontologies" / "ohi" / "user" / "1.2.3"
        val manifest = os.read(folder / "ohi-package.yaml")
        manifest should include("""dependencies: ["ohi/my-common@1.2.3"]""")
        os.read(folder / "user.yml") shouldBe user.replace(
          "- ../common  # relative",
          "- https://api.ohi.dev/files/ohi/my-common/1.2.3/common.yaml  # relative",
        )
      }
    }

    "use the schema's name for a missing title and description" in {
      withModel("common.yaml" -> common, "sub/user.yml" -> user) { (model, dir) =>
        val out = dir / "out"
        OhiPackages.write(model, dir / "LICENSE", out, "1.2.3", "abc123")

        val manifest = os.read(out / "ontologies" / "ohi" / "user" / "1.2.3" / "ohi-package.yaml")
        manifest should include("""title: "user"""")
        manifest should include("""description: "user"""")
      }
    }

    "fail on an import that is no schema of the folder" in {
      withModel("user.yaml" -> user) { (model, dir) =>
        val e = the[IllegalArgumentException] thrownBy
          OhiPackages.write(model, dir / "LICENSE", dir / "out", "1", "abc")
        e.getMessage should include("../common")
      }
    }

    "make a package of each schema in model/, depending only on each other" in {
      val root = os.Path(sys.env("MILL_WORKSPACE_ROOT"))
      val out = os.temp.dir()
      try {
        val packages = OhiPackages.write(root / "model", root / "LICENSE", out, "0.0.0", "abc")
        val names = packages.map(p => s"ohi/${p.schema.name}@0.0.0").toSet
        names.size shouldBe packages.size
        packages.foreach { p =>
          withClue(p.schema.file) {
            names should contain allElementsOf p.dependencies
            // Every import left in the written file is a URL or a linkml: one
            val written = OhiPackages.parse(p.schema.file, os.read(p.folder / p.schema.file.last))
            all(written.imports) should include(":")
          }
        }
        packages.map(_.schema.file) should contain(os.sub / "ohi" / "index-manifest.yml")
        packages.map(_.schema.file).filter(_.startsWith(os.sub / "converter" / "examples")) shouldBe
          empty
      } finally os.remove.all(out)
    }
  }

  "OhiPackages.read" should {
    "fail when two schemas would be the same package" in {
      withModel("a.yaml" -> common, "b.yaml" -> common) { (model, _) =>
        val e = the[IllegalArgumentException] thrownBy OhiPackages.read(model)
        e.getMessage should include("my-common")
      }
    }
  }
}

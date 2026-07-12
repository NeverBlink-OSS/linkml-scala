package millbuild

/** Metadata for the generated `@neverblink/linkml` npm package. */
object NpmPackage {
  val name = "@neverblink/linkml"

  def packageJson(version: String, description: String, mainFile: String): String =
    s"""{
       |  "name": "$name",
       |  "version": "$version",
       |  "description": "$description",
       |  "type": "module",
       |  "main": "$mainFile",
       |  "module": "$mainFile",
       |  "types": "index.d.ts",
       |  "exports": {
       |    ".": {
       |      "types": "./index.d.ts",
       |      "import": "./$mainFile"
       |    }
       |  },
       |  "files": [
       |    "$mainFile",
       |    "$mainFile.map",
       |    "index.d.ts",
       |    "README.md"
       |  ],
       |  "license": "Apache-2.0",
       |  "homepage": "https://github.com/NeverBlink-OSS/linkml-scala",
       |  "repository": {
       |    "type": "git",
       |    "url": "git+https://github.com/NeverBlink-OSS/linkml-scala.git"
       |  },
       |  "bugs": {
       |    "url": "https://github.com/NeverBlink-OSS/linkml-scala/issues"
       |  },
       |  "author": "NeverBlink (https://neverblink.eu)",
       |  "publishConfig": {
       |    "access": "public",
       |    "registry": "https://registry.npmjs.org/"
       |  },
       |  "keywords": [
       |    "linkml",
       |    "json-schema",
       |    "shacl",
       |    "rdf",
       |    "schema",
       |    "code-generation"
       |  ]
       |}
       |""".stripMargin
}

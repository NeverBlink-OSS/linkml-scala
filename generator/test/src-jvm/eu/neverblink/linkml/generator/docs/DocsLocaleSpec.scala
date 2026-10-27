package eu.neverblink.linkml.generator.docs

import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.Locale

/** Only on the JVM, as Scala.js has no `java.util.Locale` */
class DocsLocaleSpec extends AnyWordSpec, Matchers {
  "DocsGenerator.generateFiles" should {
    "give names that differ only in case their own files with any default locale" in {
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromString(
          """id: https://neverblink.eu/test/
            |name: test
            |classes:
            |  Item: {}
            |  item: {}
            |""".stripMargin,
        ),
      )
      val default = Locale.getDefault
      // In Turkish, the lower case of `I` is a dotless `ı`, so `Item` would not clash with `item`
      Locale.setDefault(Locale.forLanguageTag("tr"))
      try
        DocsGenerator(using sv).generateFiles().map(_.path).filter(
          _.startsWith("classes/"),
        ) shouldBe
          Seq("classes/item.md", "classes/item_2.md")
      finally Locale.setDefault(default)
    }
  }
}

package eu.neverblink.linkml.generator.translation

import eu.neverblink.linkml.schemaview.{Case, SchemaView}
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class RenamesFuzzingSpec extends AnyWordSpec, Matchers, ScalaCheckPropertyChecks {
  val word: Gen[String] = Gen.nonEmptyStringOf(Gen.alphaLowerChar)
  val num: Gen[String] = Gen.nonEmptyStringOf(Gen.numChar)
  val baseNameGen: Gen[String] = Gen.nonEmptyListOf(Gen.oneOf(word, num)).map(_.mkString("_"))

  val nonBaseNameGen: Gen[String] =
    Gen.stringOf(Gen.asciiPrintableChar.filter(_ != '"')).filter(_.nonEmpty)
  val camelNameGen: Gen[String] = Gen.alphaNumStr

  val renamerNames: Seq[String] = Seq(
    "base",
//    "uri",
    "scala",
    "graphql",
    "frictionless",
    "ossie",
    "erdiagram",
  )

  def schemaWithNames(
      className: String = "Class",
      classAttributeName: String = "class_slot",
      typeName: String = "type",
      slotName: String = "slot",
      enumName: String = "Enum",
      enumValueName: String = "ENUM_VALUE",
  ): SchemaView = SchemaView.loadSchemaViewFromString(
    s"""id: urn:fuzz
       |name: fuzz
       |
       |classes:
       |  "$className":
       |    attributes:
       |      "$classAttributeName":
       |        range: "$typeName"
       |    slots:
       |      - "$slotName"
       |
       |enums:
       |  "$enumName":
       |    permissible_values:
       |      "$enumValueName": {}
       |
       |types:
       |  "$typeName":
       |    base: str
       |
       |slots:
       |  "$slotName":
       |    range: "$enumName"
       |
       |
       |""".stripMargin,
  ).getOrElse(fail("bad schema"))

  for renamerName <- renamerNames do
    s"Renamer for $renamerName" should {
      val renamer = TranslationGenerator.resolveRenames(renamerName)
      "handle base names" when {
        "classes" in {
          forAll(baseNameGen) { name =>
            val sv = schemaWithNames(className = name)
            Case.base(renamer.className(sv.classes(name))) shouldBe name
          }
        }
        "types" in {
          forAll(baseNameGen) { name =>
            val sv = schemaWithNames(typeName = name)
            Case.base(renamer.typeName(sv.types(name))) shouldBe name
          }
        }
        "enums" in {
          forAll(baseNameGen) { name =>
            val sv = schemaWithNames(enumName = name)
            Case.base(renamer.enumName(sv.enums(name))) shouldBe name
          }
        }
        "slot" in {
          forAll(baseNameGen) { name =>
            val sv = schemaWithNames(slotName = name)
            Case.base(renamer.slotName(sv.slotDefinitions(name))) shouldBe name
          }
        }
        "class attribute" in {
          forAll(baseNameGen) { name =>
            val sv = schemaWithNames(classAttributeName = name)
            val classView = sv.classes("Class")
            Case.base(
              renamer.classAttributeName(classView, classView.cls.attributes(name)),
            ) shouldBe name
          }
        }
        "enum permissible value" in {
          forAll(baseNameGen) { name =>
            val sv = schemaWithNames(enumValueName = name)
            val enumView = sv.enums("Enum")
            Case.base(
              renamer.permissibleValueName(enumView, enumView.derivedValues.head.pv),
            ) shouldBe name
          }
        }
      }

      "handle user-provided names" when {
        "classes" in {
          forAll(baseNameGen) { nonBase =>
            val name = Case.base(nonBase)
            val sv = schemaWithNames(className = name)
            Case.base(renamer.className(sv.classes(name))) shouldBe name
          }
        }
        "types" in {
          forAll(baseNameGen) { nonBase =>
            val name = Case.base(nonBase)
            val sv = schemaWithNames(typeName = name)
            Case.base(renamer.typeName(sv.types(name))) shouldBe name
          }
        }
        "enums" in {
          forAll(baseNameGen) { nonBase =>
            val name = Case.base(nonBase)
            val sv = schemaWithNames(enumName = name)
            Case.base(renamer.enumName(sv.enums(name))) shouldBe name
          }
        }
        "slot" in {
          forAll(baseNameGen) { nonBase =>
            val name = Case.base(nonBase)
            val sv = schemaWithNames(slotName = name)
            Case.base(renamer.slotName(sv.slotDefinitions(name))) shouldBe name
          }
        }
        "class attribute" in {
          forAll(baseNameGen) { nonBase =>
            val name = Case.base(nonBase)
            val sv = schemaWithNames(classAttributeName = name)
            val classView = sv.classes("Class")
            Case.base(
              renamer.classAttributeName(classView, classView.cls.attributes(name)),
            ) shouldBe name
          }
        }
        "enum permissible value" in {
          forAll(baseNameGen) { nonBase =>
            val name = Case.base(nonBase)
            val sv = schemaWithNames(enumValueName = name)
            val enumView = sv.enums("Enum")
            Case.base(
              renamer.permissibleValueName(enumView, enumView.derivedValues.head.pv),
            ) shouldBe name
          }
        }
      }
    }
}

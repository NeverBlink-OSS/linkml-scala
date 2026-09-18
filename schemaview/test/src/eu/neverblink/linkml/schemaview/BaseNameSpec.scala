package eu.neverblink.linkml.schemaview

import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class BaseNameSpec extends AnyWordSpec, Matchers, ScalaCheckPropertyChecks {
  val word: Gen[String] = Gen.nonEmptyStringOf(Gen.alphaLowerChar)
  val num: Gen[String] = Gen.nonEmptyStringOf(Gen.numChar)
  val baseName: Gen[String] = Gen.nonEmptyListOf(Gen.oneOf(word, num)).map(_.mkString("_"))
  val nonBaseName: Gen[String] = Gen.asciiPrintableStr

  "not use underscores unless necessary" in {
    Case.baseToPascal("a_thing") shouldBe "AThing"
    Case.baseToPascal("thing_a") shouldBe "ThingA"
    Case.baseToPascal("thing_a_thing") shouldBe "ThingAThing"
  }

  "use underscores for separating multiple numbers (4_2)" in {
    Case.baseToPascal("4_2") shouldBe "4_2"
    Case.baseToCamel("4_2") shouldBe "4_2"
  }

  "use underscores to separate 1-char words (t_a_t)" in {
    Case.baseToPascal("t_a_t") shouldBe "T_A_T"
    Case.baseToCamel("t_a_t") shouldBe "tA_T"
  }

  "use underscores to separate 1-char words (e_o)" in {
    Case.baseToPascal("e_o") shouldBe "E_O"
    Case.baseToCamel("e_o") shouldBe "eO"
  }

  "not use underscores to separate numbers and alpha (a_1)" in {
    Case.baseToPascal("a_1") shouldBe "A1"
    Case.baseToCamel("a_1") shouldBe "a1"
  }

  "not use underscores to separate numbers and alpha (1_a)" in {
    Case.baseToPascal("1_a") shouldBe "1A"
    Case.baseToCamel("1_a") shouldBe "1A"
  }

  "base to base round-trip" in {
    forAll(baseName) { name =>
      Case.base(name) shouldBe name
      Case.base(Case.base(name)) shouldBe name
    }
  }

  "base to screaming snake round-trip" in {
    forAll(baseName) { name =>
      Case.base(Case.baseToScreamingSnake(name)) shouldBe name
    }
  }

  "base to camel round-trip" in {
    forAll(baseName) { name =>
      Case.base(Case.baseToCamel(name)) shouldBe name
    }
  }

  "base to pascal round-trip" in {
    forAll(baseName) { name =>
      Case.base(Case.baseToPascal(name)) shouldBe name
    }
  }

  "non-base to base round-trip" in {
    forAll(baseName) { name =>
      val base = Case.base(name)
      Case.base(base) shouldBe base
    }
  }

  "non-base to screaming snake round-trip" in {
    forAll(baseName) { name =>
      val base = Case.base(name)
      Case.base(Case.baseToScreamingSnake(base)) shouldBe base
    }
  }

  "non-base to camel round-trip" in {
    forAll(baseName) { name =>
      val base = Case.base(name)
      Case.base(Case.baseToCamel(base)) shouldBe base
    }
  }

  "non-base to pascal round-trip" in {
    forAll(baseName) { name =>
      val base = Case.base(name)
      Case.base(Case.baseToPascal(base)) shouldBe base
    }
  }
}

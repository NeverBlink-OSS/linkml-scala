package eu.neverblink.linkml.runtime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class InferenceSpec extends AnyWordSpec, Matchers {

  "inferOptional" should {
    "fill in an empty slot" in {
      inferOptional("slot", None, "inferred") shouldBe Some("inferred")
    }

    "keep a value that agrees with the inferred one" in {
      inferOptional("slot", Some("same"), "same") shouldBe Some("same")
    }

    "throw when the value contradicts the inferred one" in {
      val error = intercept[InferenceException] {
        inferOptional("slot", Some("actual"), "inferred")
      }
      error.getMessage should include("slot")
      error.getMessage should include("actual")
      error.getMessage should include("inferred")
    }
  }

  "inferRequired" should {
    "keep a value that agrees with the inferred one" in {
      inferRequired("slot", "same", "same") shouldBe "same"
    }

    "throw when the value contradicts the inferred one" in {
      val error = intercept[InferenceException] {
        inferRequired("slot", "actual", "inferred")
      }
      error.getMessage should include("slot")
      error.getMessage should include("actual")
      error.getMessage should include("inferred")
    }
  }

  "inferenceInput" should {
    "unwrap a present value" in {
      inferenceInput("slot", Some("value")) shouldBe "value"
    }

    "throw when the referenced slot has no value" in {
      val error = intercept[InferenceException] {
        inferenceInput("slot", None)
      }
      error.getMessage should include("slot")
    }
  }
}

package eu.neverblink.ohi.cli

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class VersionOrderSpec extends AnyWordSpec, Matchers {
  "version order" should {
    for (lower, higher) <- Seq(
        ("1.9.1", "1.10.1"), // digit runs compare as numbers
        ("1.0", "v1.0"), // a digit run sorts before a letter run
        ("1.0.0-beta", "1.0.0-RC1"), // letter case is ignored
        ("2.0.0", "2.0.0-20261105"), // a timestamped release of a version sorts after the original
      )
    do
      s"order $lower before $higher" in {
        VersionOrder.compare(lower, higher).sign shouldBe -1
        VersionOrder.compare(higher, lower).sign shouldBe 1
      }
  }
}

package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ClosureSpec extends AnyWordSpec, Matchers {
  "Closure" should {
    "work" in {
      val ids: Seq[Seq[Int]] = Seq(
        Seq(1, 3), // 0
        Seq(2, 5), // 1
        Seq(3), // 2
        Seq(), // 3
        Seq(), // 4
        Seq(), // 5
        Seq(), // 6
      )
      Closure.reflexive(0, ids.apply) shouldBe Seq(0, 1, 3, 2, 5) // linkml-py's "Depth-first" order
    }
    "work irreflexively" in {
      val ids: Seq[Seq[Int]] = Seq(
        Seq(1, 3), // 0
        Seq(2, 5), // 1
        Seq(3), // 2
        Seq(), // 3
        Seq(), // 4
        Seq(), // 5
        Seq(), // 6
      )
      Closure.irreflexive(0, ids.apply) shouldBe Seq(
        1,
        3,
        2,
        5,
      ) // linkml-py's "Depth-first" order
    }
    "work if there's loops" in {
      val ids: Seq[Seq[Int]] = Seq(
        Seq(1), // 0
        Seq(0), // 1
        Seq(), // 2
      )
      Closure.reflexive(0, ids.apply) shouldBe Seq(0, 1)
    }
    "work if there's long loops" in {
      val ids: Seq[Seq[Int]] = Seq(
        Seq(1), // 0
        Seq(2), // 1
        Seq(3), // 2
        Seq(4), // 3
        Seq(0), // 4
        Seq(), // 5
      )
      Closure.reflexive(0, ids.apply) shouldBe Seq(0, 1, 2, 3, 4)
    }
  }
}

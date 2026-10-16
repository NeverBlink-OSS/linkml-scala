package eu.neverblink.linkml.runtime

import eu.neverblink.linkml.runtime.FastUtils.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FastUtilsSpec extends AnyWordSpec, Matchers {

  /** Returns its argument and counts how many times it was evaluated */
  private final class Calls {
    var count = 0

    def apply[T](value: T): T = {
      count += 1
      value
    }
  }

  private val some: Option[String] = new Some("a")
  private val none: Option[String] = None

  /** Checks that the receiver is evaluated once and the argument `expectedArgCalls` times */
  private def check[T](expected: T, expectedArgCalls: Int)(
      run: (Calls, Calls) => T,
  ): Unit = {
    val receiver = new Calls
    val arg = new Calls
    run(receiver, arg) shouldBe expected
    receiver.count shouldBe 1
    arg.count shouldBe expectedArgCalls
  }

  "getOrElseFast" should {
    "work like getOrElse" in {
      check(some.getOrElse("b"), 0)((r, d) => r(some).getOrElseFast(d("b")))
      check(none.getOrElse("b"), 1)((r, d) => r(none).getOrElseFast(d("b")))
    }
  }

  "flatMapFast" should {
    "work like flatMap" in {
      val f = (s: String) => new Some(s + "!")
      check(some.flatMap(f), 1)((r, c) => r(some).flatMapFast(s => c(f(s))))
      check(None, 1)((r, c) => r(some).flatMapFast(_ => c(None)))
      check(none.flatMap(f), 0)((r, c) => r(none).flatMapFast(s => c(f(s))))
    }
  }

  "foldFast" should {
    "work like fold" in {
      check(some.fold("b")(_ + "!"), 1)((r, c) => r(some).foldFast("b")(s => c(s + "!")))
      check("a!", 0)((r, d) => r(some).foldFast(d("b"))(_ + "!"))
      check(none.fold("b")(_ + "!"), 0)((r, c) => r(none).foldFast("b")(s => c(s + "!")))
      check("b", 1)((r, d) => r(none).foldFast(d("b"))(_ + "!"))
    }
  }

  "foreachFast" should {
    "work like foreach" in {
      var seen = List.empty[String]
      check((), 1)((r, c) => r(some).foreachFast(s => c { seen = s :: seen }))
      seen shouldBe List("a")
      check((), 0)((r, c) => r(none).foreachFast(s => c { seen = s :: seen }))
      seen shouldBe List("a")
    }
  }

  "mapFast" should {
    "work like map" in {
      check(some.map(_ + "!"), 1)((r, c) => r(some).mapFast(s => c(s + "!")))
      check(some.map(_ => null), 1)((r, c) => r(some).mapFast(_ => c(null)))
      check(none.map(_ + "!"), 0)((r, c) => r(none).mapFast(s => c(s + "!")))
    }
  }

  "orElseFast" should {
    "work like orElse" in {
      val other = new Some("b")
      check(some.orElse(other), 0)((r, c) => r(some).orElseFast(c(other)))
      check(none.orElse(other), 1)((r, c) => r(none).orElseFast(c(other)))
      check(none.orElse(None), 1)((r, c) => r(none).orElseFast(c(None)))
    }

    "return the same instance when the option is not empty" in {
      (some.orElseFast(None) eq some) shouldBe true
    }
  }

  "orNullFast" should {
    "return the value or null" in {
      check("a", 0)((r, _) => r(some).orNullFast)
      check(null, 0)((r, _) => r(none).orNullFast)
    }
  }
}

package eu.neverblink.linkml.schemaview.buildinfo

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.scalajs.js

/** Guards the way [[PlatformBuild]] asks about globals that may not exist.
  *
  * `js.Dynamic.global.x` compiles to a bare `x`, so reading it throws a ReferenceError unless it is
  * wrapped in `js.typeOf` right where it is written -- passing it to a helper evaluates it first.
  * Node has both `process` and `navigator`, so nothing here would notice on its own; the test takes
  * them away to stand in for a browser, and for a runtime that has neither.
  */
class PlatformBuildSpec extends AnyWordSpec, Matchers {

  /** Run `body` with the named globals deleted, then put them back. Deleted rather than set to
    * undefined, because only a global that was never declared throws on a bare read.
    */
  private def without[T](names: String*)(body: => T): T = {
    // `js.Dynamic.global` is the global scope, which cannot be held in a value. Selecting
    // `globalThis` off it gives the same object as an ordinary value, which can be.
    val global = js.Dynamic.global.globalThis
    val saved = names.map(name => name -> global.selectDynamic(name))
    names.foreach(name => js.special.delete(global, name))
    try body
    finally saved.foreach((name, value) => global.updateDynamic(name)(value))
  }

  "PlatformBuild.runtime" should {
    "name Node when it is running on Node" in {
      PlatformBuild.runtime.value should startWith("Node.js v")
    }

    "say Browser when there is no process, the way a browser tab has none" in {
      without("process") {
        PlatformBuild.runtime shouldBe Some("Browser")
      }
    }

    "fall back rather than throw when neither global exists" in {
      without("process", "navigator") {
        PlatformBuild.runtime shouldBe Some("JavaScript")
      }
    }
  }

  extension [T](option: Option[T])
    private def value: T = option.getOrElse(fail("expected a value, got None"))
}

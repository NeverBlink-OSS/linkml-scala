package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.rdf.RdfParseException
import eu.neverblink.linkml.runtime.SchemaConversionException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BaseCommandSpec extends AnyWordSpec, Matchers {

  /** A command that stops with `err("Cannot run", e)` */
  private final class Failing(e: Throwable) extends BaseCommand[VersionOptions] {
    override def run(options: VersionOptions, remainingArgs: RemainingArgs): Unit =
      err("Cannot run", e)
  }

  private def runFailing(e: Throwable): (String, String, Int) = {
    val command = Failing(e)
    command.runTestCommandWithExitCode(Nil, args => command.main(args))
  }

  "err with an exception" should {
    "print only the reason for a schema that can't be converted" in {
      val (out, err, code) = runFailing(SchemaConversionException("The IRI <a b> isn't valid"))
      code shouldBe 1
      out shouldBe empty
      err.trim shouldBe "Cannot run: The IRI <a b> isn't valid"
    }

    "print only the reason for RDF that can't be parsed" in {
      val (_, err, code) = runFailing(RdfParseException("Expected '.'", 3, 7))
      code shouldBe 1
      err.trim shouldBe "Cannot run: Expected '.' (line 3, column 7)"
    }

    "throw a bug again, so its stack trace is printed" in {
      val bug = NullPointerException()
      (the[NullPointerException] thrownBy runFailing(bug)) should be theSameInstanceAs bug
    }
  }
}

package eu.neverblink.linkml.generator.linkml

import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class LinkMlGeneratorSpec extends AnyWordSpec, Matchers {
  "LinkMlGenerator" should {
    "do stuff" in {
      val sv = ModelCatalogue.inheritance.model
      println(LinkMlGenerator(using sv).serialize(materializeClasses = true, asJson = false))
    }
  }
}

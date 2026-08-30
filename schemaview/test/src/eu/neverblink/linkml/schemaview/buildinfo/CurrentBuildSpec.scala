package eu.neverblink.linkml.schemaview.buildinfo

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.Node

class CurrentBuildSpec extends AnyWordSpec, Matchers {
  "CurrentBuild" should {
    "report the versions the build filled in" in {
      val info = CurrentBuild.info
      // A `0.0.0-SNAPSHOT` fallback is fine (it is what a shallow clone with no tags produces),
      // but an empty string means the constant never made it into the class file.
      info.linkmlScalaVersion should not be empty
      info.metamodelVersion should fullyMatch regex raw"\d+\.\d+\.\d+"
      info.scalaVersion should startWith("3.")
    }

    "describe the platform it is running on" in {
      val info = CurrentBuild.info
      info.runtime.value should not be empty
      // Scala.js is reported only by the JavaScript build, and only there.
      info.platform match {
        case Platform.Js => info.scalaJsVersion.value should startWith("1.")
        case _ => info.scalaJsVersion shouldBe None
      }
    }

    "leave the slots it cannot know about empty" in {
      // Only the shared library knows the ABI version, so reporting it here would be a guess.
      CurrentBuild.info.abiVersion shouldBe None
    }

    "encode itself under the slot names from the LinkML schema" in {
      val keys = CurrentBuild.node() match {
        case mapping: Node.MappingNode =>
          mapping.mappings.keys.collect { case k: Node.ScalarNode => k.value }.toSet
        case other => fail(s"expected a mapping, got $other")
      }
      // Keyed by LinkML slot names, not by the Scala field names they became.
      keys should contain allOf ("linkml_scala_version", "metamodel_version", "scala_version")
      keys should not contain "linkmlScalaVersion"
      // Absent optionals are left out rather than written as null.
      keys should not contain "abi_version"
    }
  }

  extension [T](option: Option[T])
    private def value: T = option.getOrElse(fail("expected a value, got None"))
}

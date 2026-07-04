package eu.neverblink.linkml.metamodel

import eu.neverblink.linkml.metamodel.Codec.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.*
import io.circe.*

class MetamodelSpec extends AnyWordSpec, Matchers {
  "Metamodel" should {
    "decode and encode annotations.yaml" in {
      roundTrip("/annotations.yaml")
    }
    "decode and encode array.yaml" ignore {
      roundTrip("/array.yaml")
    }
    "decode and encode datasets.yaml" in {
      roundTrip("/datasets.yaml")
    }
    "decode and encode extended_types.yaml" in {
      roundTrip("/extended_types.yaml")
    }
    "decode and encode extensions.yaml" in {
      roundTrip("/extensions.yaml")
    }
    "decode and encode mappings.yaml" in {
      roundTrip("/mappings.yaml")
    }
    "decode and encode meta.yaml" in {
      roundTrip("/meta.yaml")
    }
    "decode and encode types.yaml" in {
      roundTrip("/types.yaml")
    }
    "decode and encode units.yaml" in {
      roundTrip("/units.yaml")
    }
    "decode and encode validation.yaml" in {
      roundTrip("/validation.yaml")
    }
  }

  "metamodel patches" should {
    // TODO LNK-124: remove when resolved in linkml-model
    "make rank slot inherited" in {
      val parent = SlotDefinitionImpl(
        name = "parent",
        rank = Some(123),
      )
      val child = SlotDefinitionImpl(
        name = "child",
      )
      val combined = child.combineInherited(parent, null)
      combined.rank shouldBe Some(123)
      combined.name shouldBe "child"
    }
  }

  private def roundTrip(path: String): Unit = {
    val expected = parseYaml(Resources.read(path)).getOrElse(Node.ScalarNode(null))
    val result = codec.encode(codec.decode(expected))
    yamlToJson(result) shouldBe yamlToJson(expected)
  }

  private def yamlToJson(yaml: Node): Json = yaml match {
    case Node.MappingNode(entries, _) =>
      if (entries.isEmpty) {
        Json.fromString("") // empty mappings are represented as empty strings
      } else {
        val fields = Array.newBuilder[(String, Json)]
        entries.foreach { kv =>
          val value = yamlToJson(kv._2)
          if (value != Json.False) { // skip default false values
            fields.addOne((kv._1.asYaml.trim, value))
          }
        }
        Json.obj(fields.result()*)
      }
    case Node.SequenceNode(elements, _) =>
      if (elements.sizeCompare(1) == 0) {
        yamlToJson(elements.head) // single-element sequences as the element
      } else Json.arr(elements.map(yamlToJson)*)
    case Node.ScalarNode(value, _) =>
      value match {
        case "true" | "True" | "TRUE" => Json.True
        case "false" | "False" | "FALSE" => Json.False
        case "null" | "~" | "Null" | "NULL" => Json.Null
        case s if s.nonEmpty && {
              val ch = s.charAt(0)
              Character.isDigit(ch) || ch == '-'
            } =>
          yaml.as[BigDecimal] match {
            case Right(v) => Json.fromBigDecimal(v)
            case _ => Json.fromString(value)
          }
        case _ => Json.fromString(value)
      }
    case _ => Json.Null
  }
}

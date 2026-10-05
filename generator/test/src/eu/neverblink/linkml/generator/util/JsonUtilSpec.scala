package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class JsonUtilSpec extends AnyWordSpec, Matchers {
  import JsonUtilSpec.*

  "JsonUtil.yamlToJson" should {
    "serialize a class with one field as an object" in {
      JsonUtil.yamlToJson(summon[LinkmlYamlCodec[Wrapper]].encode(Wrapper("abc"))) shouldBe
        """{
          |  "v": "abc"
          |}""".stripMargin
    }
    "serialize a class with one field annotated with '@flatten' as its value" in {
      val holder = Holder(Flat("abc"), Uri("https://example.org/x"), Reference("y"))
      JsonUtil.yamlToJson(summon[LinkmlYamlCodec[Holder]].encode(holder)) shouldBe
        """{
          |  "flat": "abc",
          |  "uri": "https://example.org/x",
          |  "ref": "y"
          |}""".stripMargin
    }
  }
}

object JsonUtilSpec {
  case class Wrapper(v: String) derives LinkmlYamlCodec

  @flatten case class Flat(v: String)

  case class Holder(flat: Flat, uri: Uri, ref: Reference[Wrapper]) derives LinkmlYamlCodec
}

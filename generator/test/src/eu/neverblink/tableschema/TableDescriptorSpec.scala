package eu.neverblink.tableschema

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import io.circe.syntax.EncoderOps

class TableDescriptorSpec extends AnyWordSpec, Matchers {
  "TableDescriptor" should {
    "serialize" in {
      println(TableDescriptor().asJson.deepDropNullValues.spaces2)
    }
  }
}

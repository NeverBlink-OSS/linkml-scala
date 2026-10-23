package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.LinkmlAny
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.virtuslab.yaml.parseYaml
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

private[cli] object ErrorOutput {
  private val mapper = JsonMapper.builder().build()

  // Imported records have distinct generated Scala types.
  def adapt[T: LinkmlYamlCodec](json: String): T =
    summon[LinkmlYamlCodec[T]].decode(parseYaml(json).fold(throw _, identity))

  /** Restores raw JSON to preserve nulls and numeric values. */
  def withCatalogResponse(json: String, response: Option[LinkmlAny]): String = {
    val data = mapper.readTree(json)
    response.foreach(value =>
      data.get("catalog_error").asInstanceOf[ObjectNode]
        .set("response_json", mapper.readTree(value.value)),
    )
    mapper.writeValueAsString(data)
  }
}

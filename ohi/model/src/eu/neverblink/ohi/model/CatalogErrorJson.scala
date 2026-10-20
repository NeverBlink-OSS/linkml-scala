package eu.neverblink.ohi.model

import eu.neverblink.linkml.runtime.{LinkmlAny, stringify}
import eu.neverblink.ohi.model.generated.error.CatalogError
import tools.jackson.core.JacksonException

/** Parses HTTP error objects and encodes catalog errors. */
object CatalogErrorJson {
  private val mapper = CatalogJson.mapper

  private[ohi] def responseObject(text: String): Option[LinkmlAny] =
    try {
      val data = mapper.readTree(text)
      Option.when(data.isObject)(LinkmlAny(mapper.writeValueAsString(data)))
    } catch {
      case _: JacksonException => None
    }

  def encode(error: CatalogError): String = {
    val data = mapper.createObjectNode()
      .put("code", stringify(error.code))
      .put("message", error.message)
      .put("operation", stringify(error.operation))
    error.url.foreach(value => data.put("url", value))
    error.status.foreach(value => data.put("status", value))
    error.responseJson.foreach(value => data.set("response_json", mapper.readTree(value.value)))
    mapper.writeValueAsString(data)
  }
}

package eu.neverblink.ohi.model

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.ohi.model.generated.lockerror.LockError

/** Encodes lock errors, omitting paths for text operations. */
object LockErrorJson {
  def encode(error: LockError): String = {
    val mapper = CatalogJson.mapper
    val data = mapper.createObjectNode()
      .put("code", stringify(error.code))
      .put("message", error.message)
      .put("operation", stringify(error.operation))
    error.path.foreach(value => data.put("path", value))
    mapper.writeValueAsString(data)
  }
}

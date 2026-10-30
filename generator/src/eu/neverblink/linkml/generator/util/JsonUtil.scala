package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.generator.options.JsonOutputFormat
import eu.neverblink.linkml.rdf.io.Utf8ByteSink
import eu.neverblink.linkml.yaml.NodeJson
import org.virtuslab.yaml.{Node, NodeOps}

import java.io.OutputStream

object JsonUtil {

  /** Serialize scala-yaml [[Node]] to pretty JSON string
    */
  def yamlToJson(yaml: Node): String = NodeJson.write(yaml)

  /** Serialize scala-yaml [[Node]] as pretty JSON straight to [[out]], skipping the string. */
  def writeJson(yaml: Node, out: OutputStream): Unit = NodeJson.write(yaml, out)

  /** Serialize scala-yaml [[Node]] in [[format]]. */
  def write(yaml: Node, format: JsonOutputFormat): String =
    if (format == JsonOutputFormat.Json) yamlToJson(yaml) else yaml.asYaml

  /** Serialize scala-yaml [[Node]] in [[format]] straight to [[out]]. */
  def write(yaml: Node, format: JsonOutputFormat, out: OutputStream): Unit =
    if (format == JsonOutputFormat.Json) writeJson(yaml, out)
    else {
      val sink = new Utf8ByteSink(out)
      sink.append(yaml.asYaml)
      sink.flush()
    }
}

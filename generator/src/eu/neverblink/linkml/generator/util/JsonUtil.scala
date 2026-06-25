package eu.neverblink.linkml.generator.util

import io.circe.Json
import org.virtuslab.yaml.{Node, NodeOps}

object JsonUtil {
  /** Convert scala-yaml [[Node]] to a circe [[Json]] AST.
   */
  def yamlToJson(yaml: Node): Json = yaml match {
    case Node.MappingNode(entries, _) =>
      val fields = Array.newBuilder[(String, Json)]
      entries.foreach { kv =>
        val value = yamlToJson(kv._2)
        if (value != Json.False) { // skip default false values
          fields.addOne((kv._1.asYaml.trim, value))
        }
      }
      Json.obj(fields.result() *)
    case Node.SequenceNode(elements, _) =>
      Json.arr(elements.map(yamlToJson) *)
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

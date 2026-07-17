package eu.neverblink.linkml.generator.util

import com.github.plokhotnyuk.jsoniter_scala.core.*
import org.virtuslab.yaml.{Node, NodeOps}

object JsonUtil {

  /** Serialize scala-yaml [[Node]] to pretty JSON string
    */
  def yamlToJson(yaml: Node): String =
    writeToString(yaml, WriterConfig.withIndentionStep(2))

  private implicit val yamlCodec: JsonValueCodec[Node] = new JsonValueCodec[Node] {
    override def decodeValue(in: JsonReader, default: Node): Node = ???

    override def encodeValue(x: Node, out: JsonWriter): Unit = x match {
      case Node.MappingNode(entries, _) =>
        out.writeObjectStart()
        entries.foreach { kv =>
          kv._2 match {
            case Node.ScalarNode(value, _)
                if value == "false" || value == "False" | value == "FALSE" =>
              () // skip default false values
            case _ =>
              out.writeKey(kv._1.asYaml.trim)
              yamlCodec.encodeValue(kv._2, out)
          }
        }
        out.writeObjectEnd()
      case Node.SequenceNode(elements, _) =>
        out.writeArrayStart()
        elements.foreach(e => yamlCodec.encodeValue(e, out))
        out.writeArrayEnd()
      case Node.ScalarNode(value, _) =>
        value match {
          case "true" | "True" | "TRUE" => out.writeVal(true)
          case "false" | "False" | "FALSE" => out.writeVal(false)
          case "null" | "~" | "Null" | "NULL" => out.writeNull()
          case s if s.nonEmpty && {
                val ch = s.charAt(0)
                Character.isDigit(ch) || ch == '-' || ch == '+' || ch == '.'
              } =>
            x.as[BigDecimal] match {
              case Right(v) => out.writeVal(v)
              case _ => out.writeVal(value)
            }
          case _ => out.writeVal(value)
        }
      case _ => out.writeNull()
    }

    override def nullValue: Node = ???
  }
}

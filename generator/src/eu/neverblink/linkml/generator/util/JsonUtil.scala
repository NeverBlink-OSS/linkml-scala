package eu.neverblink.linkml.generator.util

import com.github.plokhotnyuk.jsoniter_scala.core.*
import org.virtuslab.yaml.{Node, NodeOps, Tag}

object JsonUtil {

  /** Serialize scala-yaml [[Node]] to pretty JSON string
    */
  def yamlToJson(yaml: Node): String =
    writeToString(yaml, WriterConfig.withIndentionStep(2))

  private implicit val yamlCodec: JsonValueCodec[Node] = new JsonValueCodec[Node] {
    override def decodeValue(in: JsonReader, default: Node): Node = ???

    override def encodeValue(x: Node, out: JsonWriter): Unit = x match {
      case s: Node.ScalarNode =>
        val tag = s.tag
        if (tag eq Tag.nullTag) out.writeNull()
        else if (tag eq Tag.str) out.writeVal(s.value)
        else if (tag eq Tag.boolean) {
          s.value match {
            case "true" | "True" | "TRUE" => out.writeVal(true)
            case _ => out.writeVal(false)
          }
        } else out.writeRawVal(s.value.getBytes) // ints and floats
      case m: Node.MappingNode =>
        out.writeObjectStart()
        m.mappings.foreach { kv =>
          out.writeKey(kv._1.asYaml.trim)
          yamlCodec.encodeValue(kv._2, out)
        }
        out.writeObjectEnd()
      case s: Node.SequenceNode =>
        out.writeArrayStart()
        s.nodes.foreach(e => yamlCodec.encodeValue(e, out))
        out.writeArrayEnd()
    }

    override def nullValue: Node = ???
  }
}

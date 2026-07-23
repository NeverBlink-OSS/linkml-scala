package eu.neverblink.linkml.generator.util

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import org.virtuslab.yaml.{Node, Tag}

import scala.util.control.NonFatal

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
        else if (tag eq Tag.boolean) {
          out.writeVal(s.value.trim match {
            case "true" | "True" | "TRUE" => true
            case _ => false
          })
        } else if ((tag eq Tag.int) || (tag eq Tag.float)) {
          val value = s.value.trim
          // some valid YAML numbers cannot be serialized as JSON numbers
          val bytes = s.value.getBytes
          if (isJsonNumber(bytes)) out.writeRawVal(bytes)
          else out.writeVal(value)
        } else out.writeVal(s.value)
      case m: Node.MappingNode =>
        out.writeObjectStart()
        m.mappings.foreach { kv =>
          out.writeKey(kv._1 match {
            case s: Node.ScalarNode => s.value
            case n => writeToStringReentrant(n)
          })
          yamlCodec.encodeValue(kv._2, out)
        }
        out.writeObjectEnd()
      case s: Node.SequenceNode =>
        out.writeArrayStart()
        s.nodes.foreach(e => yamlCodec.encodeValue(e, out))
        out.writeArrayEnd()
    }

    override def nullValue: Node = ???

    private def isJsonNumber(bytes: Array[Byte]): Boolean =
      try {
        readFromArray[Float](bytes, readerConfig)
        true
      } catch {
        case ex if NonFatal(ex) => false
      }

    implicit private val floatCodec: JsonValueCodec[Float] = JsonCodecMaker.make
    private val readerConfig: ReaderConfig = ReaderConfig.withAppendHexDumpToParseException(false)
  }
}

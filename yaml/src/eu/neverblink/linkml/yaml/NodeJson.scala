package eu.neverblink.linkml.yaml

import com.github.plokhotnyuk.jsoniter_scala.core.*
import org.virtuslab.yaml.{Node, Tag}

import java.io.OutputStream
import scala.util.control.NonFatal

/** Writes scala-yaml [[Node]]s, such as those from [[LinkmlYamlCodec.encode]], as JSON. */
object NodeJson {

  /** Serialize `yaml` as pretty JSON. */
  def write(yaml: Node): String = writeToString(yaml, WriterConfig.withIndentionStep(2))

  /** Serialize `yaml` as pretty JSON straight to `out`. */
  def write(yaml: Node, out: OutputStream): Unit =
    writeToStream(yaml, out, WriterConfig.withIndentionStep(2))

  private implicit val nodeCodec: JsonValueCodec[Node] = new JsonValueCodec[Node] {
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
          val bytes = value.getBytes
          if (isJsonNumber(bytes)) out.writeRawVal(bytes) // serialize as JSON number
          else out.writeVal(value) // serialize as JSON string
        } else out.writeVal(s.value)
      case m: Node.MappingNode =>
        out.writeObjectStart()
        m.mappings.foreach { kv =>
          out.writeKey(kv._1 match {
            case s: Node.ScalarNode => s.value
            case n => writeToStringReentrant(n)
          })
          nodeCodec.encodeValue(kv._2, out)
        }
        out.writeObjectEnd()
      case s: Node.SequenceNode =>
        out.writeArrayStart()
        s.nodes.foreach(e => nodeCodec.encodeValue(e, out))
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

    implicit private val floatCodec: JsonValueCodec[Float] = new JsonValueCodec[Float] {
      override def decodeValue(in: JsonReader, default: Float): Float = in.readFloat()

      override def encodeValue(x: Float, out: JsonWriter): Unit = out.writeVal(x)

      override def nullValue: Float = 0f
    }
    private val readerConfig: ReaderConfig = ReaderConfig.withAppendHexDumpToParseException(false)
  }
}

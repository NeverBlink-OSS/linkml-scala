package eu.neverblink.ohi.model

import eu.neverblink.linkml.yaml.{LinkmlYamlCodec, NodeJson}
import org.virtuslab.yaml.{Node, parseYaml}
import scala.util.control.NonFatal

/** JSON text through the codecs of the generated classes. */
private[ohi] object ModelJson {

  /** @throws IllegalArgumentException if `text` cannot be parsed */
  def parse(text: String): Node = {
    // YAML ignores a leading byte order mark, but JSON text must not have one.
    require(!text.startsWith("﻿"), "JSON text must not start with a byte order mark")
    parseYaml(text).fold(error => throw new IllegalArgumentException(error.msg, error), identity)
  }

  /** @throws IllegalArgumentException if `text` does not decode as a `T` */
  def decode[T](codec: LinkmlYamlCodec[T], text: String): T =
    try codec.decode(parse(text))
    catch {
      case cause: IllegalArgumentException => throw cause
      case NonFatal(cause) => throw new IllegalArgumentException(cause.getMessage, cause)
    }

  def encode[T](codec: LinkmlYamlCodec[T], value: T): String = NodeJson.write(codec.encode(value))
}

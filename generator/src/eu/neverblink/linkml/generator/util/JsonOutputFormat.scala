package eu.neverblink.linkml.generator.util

/** Serialization format for the generators that can write either YAML or JSON.
  */
enum JsonOutputFormat:
  case yaml, json

object JsonOutputFormat {

  def parse(format: String): Option[JsonOutputFormat] = format.toLowerCase match {
    case "yaml" | "yml" => Some(JsonOutputFormat.yaml)
    case "json" => Some(JsonOutputFormat.json)
    case _ => None
  }

  def unknownFormat(format: String): String =
    s"Unknown output format '$format'. Supported formats: yaml, json."
}

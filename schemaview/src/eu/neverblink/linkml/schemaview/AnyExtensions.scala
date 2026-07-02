package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.runtime.LinkMlAny
import org.virtuslab.yaml.{Node, YamlDecoder, YamlError, parseYaml}

extension (any: LinkMlAny)
  /** Interpret the value of linkml:Any as YAML and return it.
    */
  def yaml: Either[YamlError, Node] = parseYaml(any.value)

  /** Interpret the value of linkml:Any as YAML and then also decode it as [[T]].
    */
  def yamlAs[T: YamlDecoder]: Either[YamlError, T] = any.yaml.flatMap(_.as[T])

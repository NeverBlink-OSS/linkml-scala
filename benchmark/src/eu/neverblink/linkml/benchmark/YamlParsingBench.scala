package eu.neverblink.linkml.benchmark

import org.virtuslab.yaml.*
import zio.blocks.schema.yaml.*
import org.openjdk.jmh.annotations.{Benchmark, Param, Setup}

import scala.compiletime.uninitialized
import scala.io.Source
import scala.util.Using

class YamlParsingBench extends CommonParams {
  @Param(Array("cgmes-core.yml", "cgmes-dynamics.yml", "TC57CIM.yml"))
  var schema: String = uninitialized

  private var yaml: String = uninitialized

  @Setup
  def setup(): Unit = yaml = Using.resource(getClass.getResourceAsStream(s"/schemas/$schema")) { in =>
    Source.fromInputStream(in, "UTF-8").mkString
  }

  @Benchmark
  def scalaYaml: Node = parseYaml(yaml).getOrElse(null)

  @Benchmark
  def zioBlocks: Yaml = YamlReader.read(yaml)
}

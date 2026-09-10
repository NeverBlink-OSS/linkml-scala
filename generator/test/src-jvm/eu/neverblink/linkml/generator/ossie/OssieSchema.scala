package eu.neverblink.linkml.generator.ossie

import com.networknt.schema.dialect.Dialects
import com.networknt.schema.resource.{MapResourceLoader, ResourceLoaders, SchemaLoader}
import com.networknt.schema.serialization.DefaultNodeReader
import com.networknt.schema.{InputFormat, Schema, SchemaRegistry}
import org.snakeyaml.engine.v2.api.LoadSettings
import tools.jackson.dataformat.yaml.{YAMLFactory, YAMLMapper}

import java.util.function.Consumer
import scala.jdk.CollectionConverters.*

/** Apache Ossie's JSON Schema for ontology definitions, as vendored under
  * `generator/test/resources/ossie`. See the README next to it.
  */
object OssieSchema {

  private val yamlSizeLimit = 256 * 1024 * 1024
  private val coreSpecUri =
    "https://raw.githubusercontent.com/apache/ossie/main/core-spec/ossie-schema.json"

  /** The vendored core spec, served verbatim.
    */
  private def coreSpec: String = {
    val url = Option(getClass.getResource("/ossie/ossie-schema.json")).getOrElse(
      sys.error("The vendored Apache Ossie core schema is not on the test classpath"),
    )
    os.read(os.Path(java.nio.file.Paths.get(url.toURI)))
  }

  private lazy val schema: Schema = {
    val url = Option(getClass.getResource("/ossie/ontology.json")).getOrElse(
      sys.error("The vendored Apache Ossie ontology schema is not on the test classpath"),
    )
    val yaml = YAMLMapper.builder(
      YAMLFactory.builder()
        .loadSettings(LoadSettings.builder().setCodePointLimit(yamlSizeLimit).build())
        .build(),
    ).build()

    val useYamlMapper: Consumer[DefaultNodeReader.Builder] = reader => {
      reader.yamlMapper(yaml)
      ()
    }
    val serveCoreSpec: Consumer[ResourceLoaders.Builder] = loaders => {
      loaders.add(MapResourceLoader(java.util.Map.of(coreSpecUri, coreSpec)))
      ()
    }
    val loadLocally: Consumer[SchemaLoader.Builder] = loader => {
      loader.resourceLoaders(serveCoreSpec)
      loader.fetchRemoteResources(false)
      ()
    }
    val configure: Consumer[SchemaRegistry.Builder] = registry => {
      registry.nodeReader(useYamlMapper)
      registry.schemaLoader(loadLocally)
      ()
    }

    SchemaRegistry.withDialect(Dialects.getDraft202012, configure)
      .getSchema(os.read(os.Path(java.nio.file.Paths.get(url.toURI))))
  }

  /** Validation messages for `document`, empty when it is a valid Ossie ontology. */
  def validate(document: String, format: InputFormat): Set[String] =
    schema.validate(document, format).asScala.map(_.toString).toSet
}

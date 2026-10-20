package eu.neverblink.ohi.cli

import com.networknt.schema.{ExecutionConfig, ExecutionContext, SchemaRegistry}
import com.networknt.schema.dialect.Dialects
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.cli.generated.declaration.{ArtifactSelection, Declaration}
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import org.virtuslab.yaml.{NodeOps, parseYaml}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import tools.jackson.databind.{DeserializationFeature, JsonNode}
import tools.jackson.databind.json.JsonMapper

private[cli] object DeclarationYaml {
  private val codec: LinkmlYamlCodec[Declaration] = LinkmlYamlCodec.derived
  private val mapper = JsonMapper.builder()
    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
  private val schema = SchemaRegistry.withDialect(Dialects.getDraft202012).getSchema(
    mapper.readTree(getClass.getResourceAsStream("/declaration.schema.json")),
  )
  private val validationConfig = ExecutionConfig.builder().formatAssertionsEnabled(true).build()

  /** Parses a declaration from YAML text.
    *
    * @return
    *   the declaration, with selections in the order they appear in `text`
    * @throws ProjectException
    *   with `InvalidDeclaration` when `text` is not valid YAML, does not match the declaration
    *   schema or lists the same selection twice
    */
  def decode(text: String): Declaration = {
    val data = try {
      val node = parseYaml(text).fold(
        error => throw new IllegalArgumentException(error.msg),
        identity,
      )
      mapper.readTree(JsonUtil.yamlToJson(node))
    } catch {
      case NonFatal(cause) =>
        throw new ProjectException(
          ProjectErrorCode.InvalidDeclaration,
          s"Cannot parse the declaration: ${cause.getMessage}. Supply valid YAML.",
          Some(cause),
        )
    }
    validate(data)
    // Construct from the validated JSON tree so numeric revision 1.0 has integer semantics.
    Declaration(
      formatVersion = data.get("format_version").intValue(),
      selections = data.get("selections").iterator().asScala.map { selection =>
        ArtifactSelection(
          baseUrl = Uri(selection.get("base_url").stringValue()),
          url = Uri(selection.get("url").stringValue()),
        )
      }.toSeq,
    )
  }

  /** Serializes `declaration` as YAML.
    *
    * @return
    *   the YAML text
    * @throws ProjectException
    *   with `InvalidDeclaration` when `declaration` does not match the declaration schema or lists
    *   the same selection twice
    */
  def encode(declaration: Declaration): String = {
    val node = codec.encode(declaration)
    validate(mapper.readTree(JsonUtil.yamlToJson(node)))
    // scala-yaml 0.3.21 renders an empty sequence as null. Keep the required empty list explicit.
    if declaration.selections.isEmpty then "format_version: 1\nselections: []\n"
    else node.asYaml
  }

  private def validate(data: JsonNode): Unit = {
    val errors = schema.validate(
      data,
      (context: ExecutionContext) => context.setExecutionConfig(validationConfig),
    )
    if !errors.isEmpty then
      throw new ProjectException(
        ProjectErrorCode.InvalidDeclaration,
        s"Invalid declaration: ${errors.asScala.mkString(", ")}. Correct ohi.yaml using revision 1 and exact selection URLs.",
      )
    val pairs = data.get("selections").iterator().asScala.map(value =>
      (value.get("base_url").stringValue(), value.get("url").stringValue()),
    ).toSeq
    if pairs.distinct.size != pairs.size then
      throw new ProjectException(
        ProjectErrorCode.InvalidDeclaration,
        "Each declaration selection pair must occur once. Remove the duplicate pair from ohi.yaml.",
      )
  }
}

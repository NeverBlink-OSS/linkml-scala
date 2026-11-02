package eu.neverblink.ohi.cli

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.cli.generated.declaration.Declaration
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import org.virtuslab.yaml.{Node, NodeOps, Tag, parseYaml}
import scala.util.control.NonFatal

private[cli] object DeclarationYaml {
  private val codec: LinkmlYamlCodec[Declaration] = LinkmlYamlCodec.derived

  /** Parses a revision-2 declaration and validates complete references and unique requirements.
    *
    * @return
    *   the declaration, with dependencies in the order they appear in `text`
    * @throws ProjectException
    *   with `InvalidDeclaration` when the declaration is malformed or unsupported
    */
  def decode(text: String): Declaration = {
    val declaration = try
      val node =
        parseYaml(text).fold(error => throw new IllegalArgumentException(error.msg), identity)
      // The generic sequence decoder accepts null as empty, but the declaration requires a list.
      node match {
        case mapping: Node.MappingNode =>
          LinkmlYamlCodec.getFields(mapping).get("dependencies") match {
            case value: Node.ScalarNode if Tag.nullTag eq value.tag =>
              LinkmlYamlCodec.decodeError("a dependencies list", value)
            case _ => ()
          }
        case _ => ()
      }
      codec.decode(node)
    catch {
      case NonFatal(cause) =>
        throw new ProjectException(
          ProjectErrorCode.InvalidDeclaration,
          s"Cannot parse ohi.yaml: ${cause.getMessage}. Use format_version: 2, default_format, download_dir and dependencies with ontology and format. See https://ohi.dev/docs/guides/projects/.",
          Some(cause),
        )
    }
    check(declaration)
    declaration
  }

  /** Serializes `declaration` as YAML with its purpose and documentation comments.
    *
    * @return
    *   the YAML text
    * @throws ProjectException
    *   with `InvalidDeclaration` when the revision, download directory, references or requirement
    *   uniqueness are invalid
    */
  def encode(declaration: Declaration): String = {
    check(declaration)
    val node = codec.encode(declaration)
    // scala-yaml 0.3.21 renders an empty sequence as null. Keep the required empty list explicit.
    val body = if declaration.dependencies.isEmpty then
      node.asYaml.replaceAll("(?m)^dependencies:$", "dependencies: []")
    else node.asYaml
    "# Ontologies used by this project.\n# Docs: https://ohi.dev/docs/guides/projects/\n" + body
  }

  private def check(declaration: Declaration): Unit = {
    DownloadDestination.validate(declaration.downloadDir)
    if declaration.formatVersion != 2 then
      throw new ProjectException(
        ProjectErrorCode.InvalidDeclaration,
        s"Declaration revision ${declaration.formatVersion} is unsupported. Use format_version: 2, default_format, download_dir and dependencies with ontology and format in ohi.yaml. See https://ohi.dev/docs/guides/projects/.",
      )
    declaration.dependencies.foreach { dependency =>
      if !OntologyReference.parse(dependency.ontology).exists(_._3.nonEmpty) then
        throw new ProjectException(
          ProjectErrorCode.InvalidDeclaration,
          s"Invalid dependency '${dependency.ontology}' in ohi.yaml. Use a complete namespace/name@version reference.",
        )
    }
    val pairs = declaration.dependencies.map(value => (value.ontology, value.format))
    if pairs.distinct.size != pairs.size then
      throw new ProjectException(
        ProjectErrorCode.InvalidDeclaration,
        "Each ontology and format pair must occur once. Remove the duplicate dependency from ohi.yaml.",
      )
  }
}

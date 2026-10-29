package eu.neverblink.ohi.cli

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.cli.generated.declaration.Declaration
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import org.virtuslab.yaml.{NodeOps, parseYaml}
import scala.util.control.NonFatal

private[cli] object DeclarationYaml {
  private val codec: LinkmlYamlCodec[Declaration] = LinkmlYamlCodec.derived

  /** Parses a declaration from YAML text. Field values are not validated.
    *
    * @return
    *   the declaration, with selections in the order they appear in `text`
    * @throws ProjectException
    *   with `InvalidDeclaration` when `text` is not a YAML declaration of revision 1 or lists the
    *   same selection twice
    */
  def decode(text: String): Declaration = {
    val declaration = try
      codec.decode(
        parseYaml(text).fold(error => throw new IllegalArgumentException(error.msg), identity),
      )
    catch {
      case NonFatal(cause) =>
        throw new ProjectException(
          ProjectErrorCode.InvalidDeclaration,
          s"Cannot parse the declaration: ${cause.getMessage}. Correct ohi.yaml.",
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
    *   with `InvalidDeclaration` when `declaration` is not revision 1 or lists the same selection
    *   twice
    */
  def encode(declaration: Declaration): String = {
    check(declaration)
    val node = codec.encode(declaration)
    // scala-yaml 0.3.21 renders an empty sequence as null. Keep the required empty list explicit.
    val body = if declaration.selections.isEmpty then "format_version: 1\nselections: []\n"
    else node.asYaml
    "# Ontologies used by this project.\n# Docs: https://ohi.dev/docs/\n" + body
  }

  private def check(declaration: Declaration): Unit = {
    if declaration.formatVersion != 1 then
      throw new ProjectException(
        ProjectErrorCode.InvalidDeclaration,
        s"Declaration revision ${declaration.formatVersion} is unsupported. Use revision 1 in ohi.yaml.",
      )
    val pairs = declaration.selections.map(value => (value.baseUrl.original, value.url.original))
    if pairs.distinct.size != pairs.size then
      throw new ProjectException(
        ProjectErrorCode.InvalidDeclaration,
        "Each declaration selection pair must occur once. Remove the duplicate pair from ohi.yaml.",
      )
  }
}

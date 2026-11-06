package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.yaml.{DecodeError, LinkmlYamlCodec}
import eu.neverblink.ohi.catalog.Reference
import eu.neverblink.ohi.model.generated.{Artifact, Format, Publication}
import org.virtuslab.yaml.StringNode

private[cli] object OntologyReference {
  private val formatCodec: LinkmlYamlCodec[Format] = LinkmlYamlCodec.derived

  enum Problem {
    case InvalidFormat(message: String)
    case Invalid(message: String)
    case Unavailable(message: String, publication: Publication)
    case Ambiguous(message: String, publication: Publication, files: Seq[Artifact])

    def message: String
  }

  /** Classifies HTTP(S) prefixes without changing the input passed to SDK validation. */
  def isUrl(input: String): Boolean =
    input.regionMatches(true, 0, "http://", 0, 7) ||
      input.regionMatches(true, 0, "https://", 0, 8)

  /** Parses the reference grammar shared by command arguments and versioned project dependencies.
    */
  def parse(input: String): Either[Problem.Invalid, (String, String, Option[String])] =
    Reference.parse(input).toRight(
      Problem.Invalid(s"Invalid ontology reference '$input'. Expected namespace/name[@version]."),
    )

  /** Accepts the exact format labels defined by the generated LinkML enum. */
  def parseFormat(label: String): Either[Problem.InvalidFormat, Format] =
    try Right(formatCodec.decode(StringNode(label)))
    catch {
      case _: DecodeError => Left(Problem.InvalidFormat(s"Invalid format '$label'."))
    }

  /** Resolves namespace/name[@version] to one advertised artifact. An explicit format is strict.
    * Otherwise evaluates `preferredFormat` and falls back to LinkML only when that format is
    * absent. Returns argument or lookup diagnostics before writes. Catalog failures propagate to
    * the caller.
    */
  def resolve(
      input: String,
      format: Option[String],
      inspect: String => Publication,
      preferredFormat: => Option[Format] = None,
  ): Either[Problem, (Publication, Artifact)] =
    for {
      preferences <- format match {
        case Some(label) =>
          parseFormat(label).map(Seq(_))
        case None => Right((preferredFormat.toSeq :+ Format.LinkmlYaml).distinct)
      }
      _ <- parse(input)
      publication = inspect(input)
      artifact <- {
        val selectedFormat = preferences.find(value =>
          publication.artifacts.exists(_.format == value),
        ).getOrElse(preferences.head)
        val label = stringify(selectedFormat)
        publication.artifacts.filter(_.format == selectedFormat) match {
          case Seq(artifact) => Right(artifact)
          case Seq() =>
            val unavailable = if preferences.size == 1 then s"Format '$label' is"
            else
              s"Formats ${preferences.map(value => s"'${stringify(value)}'").mkString(" or ")} are"
            Left(
              Problem.Unavailable(
                s"$unavailable unavailable for ontology reference '$input': ${publication.baseUrl.original}",
                publication,
              ),
            )
          case files =>
            Left(
              Problem.Ambiguous(
                s"$input has more than one file in $label format.",
                publication,
                files,
              ),
            )
        }
      }
    } yield publication -> artifact
}

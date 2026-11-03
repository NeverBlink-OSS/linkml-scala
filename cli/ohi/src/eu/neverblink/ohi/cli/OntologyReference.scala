package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.yaml.{DecodeError, LinkmlYamlCodec}
import eu.neverblink.ohi.model.generated.{Artifact, Format, Publication}
import eu.neverblink.ohi.model.generated.search.{SearchHit, SearchResult}
import eu.neverblink.ohi.model.generated.searchrequest.SearchRequest
import scala.annotation.tailrec
import org.virtuslab.yaml.StringNode

private[cli] object OntologyReference {
  private val Reference = "(?U)([^/@\\s`]+)/([^/@\\s`]+)(?:@([^/@\\s+`]+))?".r
  private val bareFilterValue = "[A-Za-z0-9_.-]+".r
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
    input match {
      case Reference(namespace, name, version) => Right((namespace, name, Option(version)))
      case _ =>
        Left(
          Problem.Invalid(
            s"Invalid ontology reference '$input'. Expected namespace/name[@version].",
          ),
        )
    }

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
      search: SearchRequest => SearchResult,
      inspect: String => Publication,
      preferredFormat: => Option[Format] = None,
  ): Either[Problem, (Publication, Artifact)] =
    for {
      preferences <- format match {
        case Some(label) =>
          parseFormat(label).map(Seq(_))
        case None => Right((preferredFormat.toSeq :+ Format.LinkmlYaml).distinct)
      }
      publication <- resolvePublication(input, search, inspect)
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

  /** Looks up namespace/name[@version] and inspects its advertised URL, checking publication
    * identity without selecting an artifact. Catalog failures propagate to the caller.
    */
  def resolvePublication(
      input: String,
      search: SearchRequest => SearchResult,
      inspect: String => Publication,
  ): Either[Problem.Invalid, Publication] =
    parse(input).flatMap { (namespace, name, requestedVersion) =>
      val filter = (Seq("namespace" -> namespace, "name" -> name) ++
        requestedVersion.map("version" -> _)).map { (field, value) =>
        val quoted = if bareFilterValue.matches(value) then value else s"`$value`"
        s"$field:=$quoted"
      }.mkString(" && ")

      @tailrec
      def find(page: Int): Either[Problem.Invalid, SearchHit] = {
        val result = search(
          SearchRequest(
            q = "*",
            page = Some(page),
            perPage = Some(250),
            filterBy = Some(filter),
            sortBy = Some("published_at:desc"),
          ),
        )
        if result.searchCutoff then
          Left(
            Problem.Invalid(
              s"Search stopped early for ontology reference '$input'. Try the same command again.",
            ),
          )
        else
          result.hits.find(hit =>
            hit.namespace == namespace && hit.name == name &&
              requestedVersion.forall(_ == hit.version),
          ) match {
            case Some(hit) => Right(hit)
            case None =>
              val offset = (result.page.toLong - 1) * result.perPage.toLong
              val expected =
                math.min(result.perPage.toLong, math.max(0L, result.total.toLong - offset))
              if result.hits.size.toLong < expected then
                Left(
                  Problem.Invalid(
                    s"Incomplete search for ontology reference '$input' on page ${result.page}. Try the same command again.",
                  ),
                )
              else if offset + result.perPage.toLong < result.total.toLong then
                find(result.page + 1)
              else
                Left(
                  Problem.Invalid(
                    s"No matching release for ontology reference '$input'. Check its namespace, name and version with 'ohi search'.",
                  ),
                )
          }
      }

      find(1).flatMap { hit =>
        val publication = inspect(hit.baseUrl.original)
        if publication.baseUrl.original != hit.baseUrl.original ||
          publication.namespace != hit.namespace || publication.name != hit.name ||
          publication.version != hit.version
        then
          Left(
            Problem.Invalid(
              s"Release metadata does not match ontology reference '$input': ${publication.baseUrl.original}. Report the inconsistent metadata to the catalog operator.",
            ),
          )
        else Right(publication)
      }
    }
}

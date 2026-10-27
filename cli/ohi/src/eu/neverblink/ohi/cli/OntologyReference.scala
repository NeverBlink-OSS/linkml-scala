package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.ohi.model.generated.{Artifact, Format, Publication}
import eu.neverblink.ohi.model.generated.search.{SearchHit, SearchResult}
import eu.neverblink.ohi.model.generated.searchrequest.SearchRequest
import scala.annotation.tailrec

private[cli] object OntologyReference {
  private val Reference = "(?U)([^/@\\s`]+)/([^/@\\s`]+)(?:@([^/@\\s+`]+))?".r
  private val bareFilterValue = "[A-Za-z0-9_.-]+".r
  private val formats = Seq[Format](
    Format.OwlTurtle,
    Format.OwlRdfxml,
    Format.RdfsTurtle,
    Format.LinkmlYaml,
    Format.OssieYaml,
  ).map(value => stringify(value) -> value).toMap

  /** Classifies HTTP(S) prefixes without changing the input passed to SDK validation. */
  def isUrl(input: String): Boolean =
    input.regionMatches(true, 0, "http://", 0, 7) ||
      input.regionMatches(true, 0, "https://", 0, 8)

  /** Resolves namespace/name[@version] to one advertised artifact of the required format. Returns
    * argument or lookup diagnostics before writes. Catalog failures propagate to the caller.
    */
  def resolve(
      input: String,
      format: Option[String],
      search: SearchRequest => SearchResult,
      inspect: String => Publication,
  ): Either[String, (Publication, Artifact)] =
    for {
      label <- format.toRight(s"--format is required for ontology reference '$input'.")
      selectedFormat <- formats.get(label).toRight(s"Invalid format '$label'.")
      selection <- input match {
        case Reference(namespace, name, version) =>
          val requestedVersion = Option(version)
          val filter = (Seq("namespace" -> namespace, "name" -> name) ++
            requestedVersion.map("version" -> _)).map { (field, value) =>
            val quoted = if bareFilterValue.matches(value) then value else s"`$value`"
            s"$field:=$quoted"
          }.mkString(" && ")

          @tailrec
          def find(page: Int): Either[String, SearchHit] = {
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
              Left(s"Search stopped early for ontology reference '$input'.")
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
                      s"Incomplete search for ontology reference '$input' on page ${result.page}.",
                    )
                  else if offset + result.perPage.toLong < result.total.toLong then
                    find(result.page + 1)
                  else Left(s"No matching release for ontology reference '$input'.")
              }
          }

          find(1).flatMap { hit =>
            val publication = inspect(hit.baseUrl.original)
            if publication.baseUrl.original != hit.baseUrl.original ||
              publication.namespace != hit.namespace || publication.name != hit.name ||
              publication.version != hit.version
            then
              Left(
                s"Inspected publication does not match ontology reference '$input': ${hit.baseUrl.original}",
              )
            else
              publication.artifacts.filter(_.format == selectedFormat) match {
                case Seq(artifact) => Right(publication -> artifact)
                case Seq() =>
                  Left(
                    s"Format '$label' is unavailable for ontology reference '$input': ${hit.baseUrl.original}",
                  )
                case _ =>
                  Left(
                    s"Format '$label' is ambiguous for ontology reference '$input': ${hit.baseUrl.original}",
                  )
              }
          }
        case _ =>
          Left(
            s"Invalid ontology reference '$input'. Expected namespace/name[@version] without backticks.",
          )
      }
    } yield selection
}

package eu.neverblink.ohi.catalog

import eu.neverblink.linkml.runtime.{LinkmlAny, extraSlotsAllowed, named}
import eu.neverblink.ohi.model.generated.search.{FacetCount, SearchHit, SearchResult}

/** The parts of a Typesense search response that make up a [[SearchResult]]. */
@extraSlotsAllowed
private[catalog] final case class TypesenseResponse(
    hits: Seq[TypesenseHit],
    found: Int,
    page: Int,
    @named("request_params")
    requestParams: TypesenseParams,
    @named("search_cutoff")
    searchCutoff: Boolean,
    @named("facet_counts")
    facetCounts: Seq[FacetCount] = Seq(),
    @named("grouped_hits")
    groupedHits: Option[LinkmlAny] = None,
) {
  def result: SearchResult = SearchResult(
    facetCounts = facetCounts,
    hits = hits.map(_.document),
    page = page,
    perPage = requestParams.perPage,
    searchCutoff = searchCutoff,
    total = found,
  )
}

@extraSlotsAllowed
private[catalog] final case class TypesenseHit(document: SearchHit)

@extraSlotsAllowed
private[catalog] final case class TypesenseParams(@named("per_page") perPage: Int)

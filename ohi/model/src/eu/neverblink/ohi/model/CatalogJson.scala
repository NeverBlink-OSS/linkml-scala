package eu.neverblink.ohi.model

import com.networknt.schema.{ExecutionConfig, ExecutionContext, SchemaRegistry}
import com.networknt.schema.dialect.Dialects
import eu.neverblink.linkml.runtime.{LinkmlDateTime, Uri, stringify}
import eu.neverblink.ohi.model.generated.config.CatalogConfig
import eu.neverblink.ohi.model.generated.search.*
import eu.neverblink.ohi.model.generated.searchrequest.SearchRequest
import scala.jdk.CollectionConverters.*
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Validates catalog inputs and decodes Typesense search results. */
private[ohi] object CatalogJson {
  private[model] val mapper = JsonMapper.builder().build()
  private val schemas = Seq("catalog-config", "search-request", "search").map { name =>
    name -> SchemaRegistry.withDialect(Dialects.getDraft202012).getSchema(
      mapper.readTree(getClass.getResourceAsStream(s"/$name.schema.json")),
    )
  }.toMap
  private val validationConfig = ExecutionConfig.builder().formatAssertionsEnabled(true).build()
  private val formats = Seq[Format](
    Format.OwlTurtle,
    Format.OwlRdfxml,
    Format.RdfsTurtle,
    Format.LinkmlYaml,
    Format.OssieYaml,
  ).map(value => stringify(value) -> value).toMap

  private def validate(name: String, data: JsonNode): Unit = {
    val errors = schemas(name).validate(
      data,
      (context: ExecutionContext) => context.setExecutionConfig(validationConfig),
    )
    require(errors.isEmpty, errors.asScala.mkString(", "))
  }

  def validateConfig(config: CatalogConfig): Unit = {
    val data = mapper.createObjectNode()
    config.searchUrl.foreach(value => data.put("search_url", value.original))
    config.queryBy.foreach(value => data.put("query_by", value))
    config.timeoutMs.foreach(value => data.put("timeout_ms", value))
    validate("catalog-config", data)
    require(
      config.searchUrl.isDefined == config.queryBy.isDefined,
      "search_url and query_by must be supplied together",
    )
  }

  def validateRequest(request: SearchRequest): Unit = {
    val data = mapper.createObjectNode().put("q", request.q)
    request.page.foreach(value => data.put("page", value))
    request.perPage.foreach(value => data.put("per_page", value))
    request.filterBy.foreach(value => data.put("filter_by", value))
    request.sortBy.foreach(value => data.put("sort_by", value))
    request.facetBy.foreach(value => data.put("facet_by", value))
    request.maxFacetValues.foreach(value => data.put("max_facet_values", value))
    validate("search-request", data)
  }

  def decodeSearch(text: String, page: Int, perPage: Int): SearchResult = {
    val raw = try mapper.readTree(text)
    catch {
      case cause: JacksonException =>
        throw new IllegalArgumentException(cause.getMessage, cause)
    }
    require(!raw.has("grouped_hits"), "grouped search responses are unsupported")
    require(raw.path("hits").isArray, "hits must be an array")
    val hits = mapper.createArrayNode()
    raw.get("hits").iterator().asScala.foreach { hit =>
      hits.add(hit.get("document"))
    }
    val data = mapper.createObjectNode()
    data.set("hits", hits)
    data.set("total", raw.get("found"))
    data.set("page", raw.get("page"))
    data.set("per_page", raw.path("request_params").get("per_page"))
    data.set("search_cutoff", raw.get("search_cutoff"))
    if (raw.has("facet_counts")) data.set("facet_counts", raw.get("facet_counts"))
    validate("search", data)
    for (field <- Seq("total", "page")) {
      require(data.get(field).canConvertToInt, s"$field exceeds the Scala Int range")
    }
    require(data.get("page").intValue() == page, "response page differs from requested page")
    require(
      data.get("per_page").intValue() == perPage,
      "response per_page differs from requested per_page",
    )
    require(hits.size() <= perPage, "response contains more hits than requested")
    SearchResult(
      facetCounts = data.path("facet_counts").iterator().asScala.map { facet =>
        FacetCount(
          fieldName = facet.get("field_name").stringValue(),
          counts = facet.get("counts").iterator().asScala.map { value =>
            require(value.get("count").canConvertToInt, "facet count exceeds the Scala Int range")
            FacetValueCount(
              count = value.get("count").intValue(),
              value = value.get("value").stringValue(),
            )
          }.toSeq,
        )
      }.toSeq,
      hits = hits.iterator().asScala.map(hit =>
        SearchHit(
          baseUrl = Uri(hit.get("base_url").stringValue()),
          description = hit.get("description").stringValue(),
          formats =
            hit.get("formats").iterator().asScala.map(value => formats(value.stringValue())).toSeq,
          keywords = hit.path("keywords").iterator().asScala.map(_.stringValue()).toSeq,
          licenses = hit.get("licenses").iterator().asScala.map(_.stringValue()).toSeq,
          name = hit.get("name").stringValue(),
          namespace = hit.get("namespace").stringValue(),
          publishedAt = LinkmlDateTime(hit.get("published_at").stringValue()),
          title = hit.get("title").stringValue(),
          version = hit.get("version").stringValue(),
        ),
      ).toSeq,
      page = page,
      perPage = perPage,
      searchCutoff = data.get("search_cutoff").booleanValue(),
      total = data.get("total").intValue(),
    )
  }
}

package eu.neverblink.linkml.generator.owl.config

import eu.neverblink.linkml.generator.owl.OwlImportNames
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.virtuslab.yaml.parseYaml

/** Reading [[OwlImportConfig]], which is generated from `model/owl-import-config.yaml`, and the
  * defaults that depend on code.
  */
object OwlImportConfigs {

  given codec: LinkmlYamlCodec[OwlImportConfig] = LinkmlYamlCodec.derived

  /** Read a config from YAML (or JSON). */
  def parse(yaml: String): OwlImportConfig =
    parseYaml(yaml) match {
      case Right(node) => codec.decode(node)
      case Left(error) => throw IllegalArgumentException(s"Not a readable config: ${error.msg}")
    }

  /** Default properties for each metaslot, in order of preference. Each list starts with the
    * metaslot's property in the LinkML metamodel, which the OWL generator writes. The exceptions
    * are `title`, written as `rdfs:label`, and `description`, written as `rdfs:comment` unless
    * asked for `skos:definition`. If a term has both, the definition becomes the description.
    * `rdfs:comment` comes before `dcterms:description`, which Dublin Core uses for usage notes.
    *
    * A value goes to the first metaslot that lists its property and still has room. So a second
    * `rdfs:comment` goes to `comments` once `description` has one.
    */
  val defaultMetadata: Seq[(String, Seq[String])] = Seq(
    "title" -> Seq("rdfs:label", "dcterms:title", "dc:title", "skos:prefLabel"),
    "description" -> Seq(
      "skos:definition",
      "obo:IAO_0000115",
      "rdfs:comment",
      "dcterms:description",
      "dc:description",
    ),
    "aliases" -> Seq(
      "skos:altLabel",
      "oboInOwl:hasExactSynonym",
      "rdfs:label",
      "skos:prefLabel",
    ),
    "comments" -> Seq("skos:note", "rdfs:comment", "skos:scopeNote"),
    "notes" -> Seq("skos:editorialNote", "skos:historyNote", "skos:changeNote"),
    "examples" -> Seq("skos:example", "obo:IAO_0000112"),
    "see_also" -> Seq("rdfs:seeAlso"),
    "source" -> Seq("dcterms:source", "dc:source"),
    "contributors" -> Seq("dcterms:contributor", "dcterms:creator", "dc:creator", "dc:contributor"),
    "created_by" -> Seq("pav:createdBy"),
    "created_on" -> Seq("pav:createdOn", "dcterms:created", "dcterms:issued"),
    "last_updated_on" -> Seq("pav:lastUpdatedOn", "dcterms:modified"),
    "modified_by" -> Seq("oslc:modifiedBy"),
    "status" -> Seq("bibo:status"),
    "in_language" -> Seq("schema:inLanguage"),
    "keywords" -> Seq("schema:keywords"),
    "categories" -> Seq("dcterms:subject"),
    "mappings" -> Seq("skos:mappingRelation"),
    "exact_mappings" -> Seq("skos:exactMatch"),
    "close_mappings" -> Seq("skos:closeMatch"),
    "related_mappings" -> Seq("skos:relatedMatch"),
    "narrow_mappings" -> Seq("skos:narrowMatch"),
    "broad_mappings" -> Seq("skos:broadMatch"),
    "deprecated" -> Seq("owl:deprecated"),
    "license" -> Seq("dcterms:license", "dc:rights", "cc:license"),
    "version" -> Seq("pav:version", "owl:versionInfo"),
  )

  extension (c: OwlImportConfig) {

    /** The default metadata sources, with this config's overrides applied. */
    def metadataSources: Seq[(String, Seq[String])] = {
      val overrides = c.metadata.view.mapValues(_.properties).toMap
      // Expand the defaults with the well-known prefixes only, not the ontology's: FRBR, for
      // example, binds `skos` to an old SKOS namespace.
      defaultMetadata.map((slot, properties) =>
        slot -> overrides.getOrElse(slot, properties.map(OwlImportNames.expand(_, Nil))),
      ) ++ overrides.filterNot((slot, _) => defaultMetadata.exists(_._1 == slot))
    }

    def prefixMap: Map[String, String] = c.prefixes.view.mapValues(_.namespace).toMap

    def renameMap: Map[String, String] = c.renames.view.mapValues(_.newName).toMap

    def datatypeMap: Map[String, String] = c.datatypes.view.mapValues(_.typeName).toMap

    def importMap: Map[String, String] = c.imports.view.mapValues(_.schema).toMap

    def classStyle: NameStyle = c.naming.flatMap(_.classes).getOrElse(NameStyle.Keep)

    def slotStyle: NameStyle = c.naming.flatMap(_.slots).getOrElse(NameStyle.Snake)

    def permissibleValueStyle: NameStyle =
      c.naming.flatMap(_.permissibleValues).getOrElse(NameStyle.Keep)

    def someValuesFromStyle: SomeValuesFrom = c.someValuesFrom.getOrElse(SomeValuesFrom.Required)
  }
}

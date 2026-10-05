package eu.neverblink.linkml.generator.owl.config

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[OwlImportConfig]] LinkML class
  *
  * @inheritdoc
  */
final case class OwlImportConfigImpl(
    @serializeDefault
    annotations: Boolean = true,
    @simpleDict
    datatypes: Map[String, DatatypeMappingImpl] = Map(),
    @named("default_prefix")
    defaultPrefix: Option[String] = None,
    @named("enums_from_individuals")
    @serializeDefault
    enumsFromIndividuals: Boolean = true,
    @simpleDict
    imports: Map[String, ImportMappingImpl] = Map(),
    @simpleDict
    metadata: Map[String, MetadataSourcesImpl] = Map(),
    @serializeDefault
    multivalued: Boolean = true,
    name: Option[String] = None,
    naming: Option[NamingImpl] = None,
    @simpleDict
    prefixes: Map[String, NamespacePrefixImpl] = Map(),
    @simpleDict
    renames: Map[String, TermRenameImpl] = Map(),
    @named("schema_id")
    schemaId: Option[String] = None,
    @named("some_values_from")
    @serializeDefault
    someValuesFrom: Option[SomeValuesFrom] = Some(SomeValuesFrom.Required),
) extends OwlImportConfig {

  override def infer(): OwlImportConfigImpl =
    this
}

/** Settings for the OWL importer.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/owl-import-config
  */
abstract class OwlImportConfig {

  /** Whether to keep annotations that no metaslot uses, as LinkML `annotations`.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def annotations: Boolean

  /** LinkML types for datatype IRIs, on top of the built-in XSD and RDF ones.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def datatypes: Map[String, DatatypeMappingImpl]

  /** Prefix of the namespace most of the ontology's terms are in. Detected if not set.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def defaultPrefix: Option[String]

  /** Whether a class that is just a set of named individuals, and is the range of some property,
    * becomes an enum. A class defined with `owl:oneOf` always does.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def enumsFromIndividuals: Boolean

  /** LinkML schemas to import instead of `owl:imports`, keyed by ontology IRI. Other `owl:imports`
    * are left out with a warning.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def imports: Map[String, ImportMappingImpl]

  /** Annotation properties to read each metaslot from, in order of preference. Replaces the default
    * list only for the metaslots it lists.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def metadata: Map[String, MetadataSourcesImpl]

  /** Whether slots are multivalued unless the property is functional. On by default, because OWL
    * allows any number of values.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def multivalued: Boolean

  /** The schema `name`. Defaults to the ontology's `rdfs:label` if it is a valid name, else the
    * default prefix.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def name: Option[String]

  /** How names are made from IRIs.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def naming: Option[NamingImpl]

  /** Extra prefixes, on top of the ones the ontology declares.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def prefixes: Map[String, NamespacePrefixImpl]

  /** Fixed names for specific terms, keyed by IRI or CURIE.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def renames: Map[String, TermRenameImpl]

  /** The schema `id`. Defaults to the ontology IRI.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def schemaId: Option[String]

  /** How to read `owl:someValuesFrom`.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def someValuesFrom: Option[SomeValuesFrom]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): OwlImportConfig
}

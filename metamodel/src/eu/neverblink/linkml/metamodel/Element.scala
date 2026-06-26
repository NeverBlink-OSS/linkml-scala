package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** A named element in the model
  *
  * @see
  *   https://en.wikipedia.org/wiki/Data_element
  * @see
  *   Aliases: data element, object
  */
abstract class Element extends Extensible, Annotatable, CommonMetadata {

  /** The unique name of the element within the context of the schema. Name is combined with the
    * default prefix to form the globally unique subject of the target class.
    *
    * @see
    *   https://en.wikipedia.org/wiki/Data_element_name
    * @see
    *   https://linkml.io/linkml/faq/modeling.html#why-are-my-class-names-translated-to-camelcase
    * @see
    *   Aliases: short name, unique name
    */
  def name: String

  /** An established standard to which the element conforms.
    *
    * @see
    *   https://w3id.org/linkml/implements
    */
  def conformsTo: Option[String]

  /** The native URI of the element. This is always within the namespace of the containing schema.
    * Contrast with the assigned URI, via class_uri or slot_uri
    *
    * @see
    *   https://w3id.org/linkml/class_uri
    * @see
    *   https://w3id.org/linkml/slot_uri
    * @note
    *   Formed by combining the default_prefix with the normalized element name
    */
  def definitionUri: Option[UriOrCurie]

  /** An allowed list of prefixes for which identifiers must conform. The identifier of this class
    * or slot must begin with the URIs referenced by this prefix
    *
    * @see
    *   https://github.com/linkml/linkml-model/issues/28
    * @note
    *   Order of elements may be used to indicate priority order
    * @note
    *   If identifiers are treated as CURIEs, then the CURIE must start with one of the indicated
    *   prefixes followed by `:` (_should_ start if the list is open)
    * @note
    *   If identifiers are treated as URIs, then the URI string must start with the expanded for of
    *   the prefix (_should_ start if the list is open)
    */
  def idPrefixes: Seq[String]

  /** If true, then the id_prefixes slot is treated as being closed, and any use of an id that does
    * not have this prefix is considered a violation.
    *
    * @see
    *   https://github.com/linkml/linkml/issues/194
    */
  def idPrefixesAreClosed: Boolean

  /** An element in another schema which this element conforms to. The referenced element is not
    * imported into the schema for the implementing element. However, the referenced schema may be
    * used to check conformance of the implementing element.
    */
  def implements: Seq[UriOrCurie]

  /** An element in another schema which this element instantiates.
    */
  def instantiates: Seq[UriOrCurie]

  def localNames: Map[String, LocalNameImpl]
}

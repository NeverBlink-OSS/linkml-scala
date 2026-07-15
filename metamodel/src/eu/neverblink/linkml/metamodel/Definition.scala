package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Abstract base class for core metaclasses
  *
  * @see
  *   https://en.wikipedia.org/wiki/Data_element_definition
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class Definition extends Element {

  /** A primary parent class or slot from which inheritable metaslots are propagated from. While
    * multiple inheritance is not allowed, mixins can be provided effectively providing the same
    * thing. The semantics are the same when translated to formalisms that allow MI (e.g. RDFS/OWL).
    * When translating to a SI framework (e.g. java classes, python classes) then is a is used. When
    * translating a framework without polymorphism (e.g. json-schema, solr document schema) then is
    * a and mixins are recursively unfolded
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def isA: Option[Reference[Definition]]

  /** A collection of secondary parent classes or slots from which inheritable metaslots are
    * propagated from.
    *
    * @see
    *   https://en.wikipedia.org/wiki/Mixin
    * @see
    *   Aliases: traits
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Mixins act in the same way as parents (is_a). They allow a model to have a primary strict
    *   hierarchy, while keeping the benefits of multiple inheritance
    */
  def mixins: Seq[Reference[Definition]]

  /** Indicates the class or slot cannot be directly instantiated and is intended for grouping
    * purposes.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def `abstract`: Boolean

  /** Used to extend class or slot definitions. For example, if we have a core schema where a gene
    * has two slots for identifier and symbol, and we have a specialized schema for my_organism
    * where we wish to add a slot systematic_name, we can avoid subclassing by defining a class
    * gene_my_organism, adding the slot to this class, and then adding an apply_to pointing to the
    * gene class. The new slot will be 'injected into' the gene class.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def applyTo: Seq[Reference[Definition]]

  /** Indicates the class or slot is intended to be inherited from without being an is_a parent.
    * mixins should not be inherited from using is_a, except by other mixins.
    *
    * @see
    *   https://en.wikipedia.org/wiki/Mixin
    * @see
    *   Aliases: trait
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def mixin: Boolean

  /** Used on a slot that stores the string serialization of the containing object. The syntax
    * follows python formatted strings, with slot names enclosed in {}s. These are expanded using
    * the values of those slots.\nWe call the slot with the serialization the s-slot, the slots used
    * in the {}s are v-slots. If both s-slots and v-slots are populated on an object then the value
    * of the s-slot should correspond to the expansion.\nImplementations of frameworks may choose to
    * use this property to either (a) PARSE: implement automated normalizations by parsing
    * denormalized strings into complex objects (b) GENERATE: implement automated to_string labeling
    * of complex objects\nFor example, a Measurement class may have 3 fields: unit, value, and
    * string_value. The string_value slot may have a string_serialization of {value}{unit} such that
    * if unit=cm and value=2, the value of string_value shouldd be 2cm
    *
    * @see
    *   https://github.com/linkml/issues/128
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def stringSerialization: Option[String]

  /** The identifier of a "value set" -- a set of identifiers that form the possible values for the
    * range of a slot. Note: this is different than 'subproperty_of' in that 'subproperty_of' is
    * intended to be a single ontology term while 'values_from' is the identifier of an entire value
    * set. Additionally, this is different than an enumeration in that in an enumeration, the values
    * of the enumeration are listed directly in the model itself. Setting this property on a slot
    * does not guarantee an expansion of the ontological hierarchy into an enumerated list of
    * possible values in every serialization of the model.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def valuesFrom: Seq[UriOrCurie]
}

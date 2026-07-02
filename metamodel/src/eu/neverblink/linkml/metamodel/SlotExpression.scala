package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** An expression that constrains the range of values a slot can take
  */
trait SlotExpression extends Expression {

  /** True means that slot can have more than one value and should be represented using a list or
    * collection structure.
    */
  def multivalued: Boolean

  /** True means that the slot must be present in instances of the class definition
    */
  def required: Boolean

  /** True means that the slot should be present in instances of the class definition, but this is
    * not required
    *
    * @see
    *   https://github.com/linkml/linkml/issues/177
    * @note
    *   This is to be used where not all data is expected to conform to having a required field
    * @note
    *   If a slot is recommended, and it is not populated, applications must not treat this as an
    *   error. Applications may use this to inform the user of missing data
    */
  def recommended: Boolean

  /** True means that keyed or identified slot appears in an outer structure by value. False means
    * that only the key or identifier for the slot appears within the domain, referencing a
    * structure that appears elsewhere.
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/06mapping/#collection-forms
    * @see
    *   https://linkml.io/linkml/schemas/inlining.html
    * @note
    *   Classes without keys or identifiers are necessarily inlined as lists
    * @note
    *   Only applicable in tree-like serializations, e.g json, yaml
    */
  def inlined: Boolean

  /** True means that an inlined slot is represented as a list of range instances. False means that
    * an inlined slot is represented as a dictionary, whose key is the slot key or identifier and
    * whose value is the range instance.
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/06mapping/#collection-forms
    * @see
    *   https://linkml.io/linkml/schemas/inlining.html
    * @note
    *   The default loader will accept either list or dictionary form as input. This parameter
    *   controls internal representation and output.
    * @note
    *   A keyed or identified class with one additional slot can be input in a third form, a
    *   dictionary whose key is the key or identifier and whose value is the one additional element.
    *   This form is still stored according to the inlined_as_list setting.
    */
  def inlinedAsList: Boolean

  /** The string value of the slot must conform to this regular expression expressed in the string
    */
  def pattern: Option[String]

  /** Holds if at least one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def anyOf: Seq[AnonymousSlotExpressionImpl]

  /** Holds if only one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def exactlyOneOf: Seq[AnonymousSlotExpressionImpl]

  /** Holds if none of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def noneOf: Seq[AnonymousSlotExpressionImpl]

  /** Holds if all of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def allOf: Seq[AnonymousSlotExpressionImpl]

  /** The value of the slot is multivalued with all members satisfying the condition
    */
  def allMembers: Option[AnonymousSlotExpressionImpl]

  /** Coerces the value of the slot into an array and defines the dimensions of that array
    */
  def array: Option[ArrayExpressionImpl]

  /** A collection of enum bindings that specify how a slot can be bound to a permissible value from
    * an enumeration.\nLinkML provides enums to allow string values to be restricted to one of a set
    * of permissible values (specified statically or dynamically).\nEnum bindings allow enums to be
    * bound to any object, including complex nested objects. For example, given a (generic) class
    * Concept with slots id and label, it may be desirable to restrict the values the id takes on in
    * a given context. For example, a HumanSample class may have a slot for representing sample
    * site, with a range of concept, but the values of that slot may be restricted to concepts from
    * a particular branch of an anatomy ontology.
    */
  def bindings: Seq[EnumBindingImpl]

  /** An inlined enumeration
    */
  def enumRange: Option[EnumExpressionImpl]

  /** The value of the slot must equal the value of the evaluated expression
    *
    * @see
    *   https://linkml.io/linkml/developers/inference.html
    * @see
    *   https://linkml.io/linkml/schemas/advanced.html#equals-expression
    * @note
    *   For example, a 'length' slot may have an equals_expression with value '(end-start)+1'
    */
  def equalsExpression: Option[String]

  /** The slot must have range of a number and the value of the slot must equal the specified value
    */
  def equalsNumber: Option[Int]

  /** The slot must have range string and the value of the slot must equal the specified value
    */
  def equalsString: Option[String]

  /** The slot must have range string and the value of the slot must equal one of the specified
    * values
    */
  def equalsStringIn: Seq[String]

  /** The exact number of entries for a multivalued slot
    *
    * @note
    *   If exact_cardinality is set, then minimum_cardinalty and maximum_cardinality must be unset
    *   or have the same value
    */
  def exactCardinality: Option[Int]

  /** The value of the slot is multivalued with at least one member satisfying the condition
    */
  def hasMember: Option[AnonymousSlotExpressionImpl]

  /** Causes the slot value to be interpreted as a uriorcurie after prefixing with this string
    */
  def implicitPrefix: Option[String]

  /** The maximum number of entries for a multivalued slot
    *
    * @note
    *   Maximum_cardinality cannot be less than minimum_cardinality
    */
  def maximumCardinality: Option[Int]

  /** For ordinal ranges, the value must be equal to or lower than this
    *
    * @see
    *   Aliases: high value
    * @note
    *   Range to be refined to an "Ordinal" metaclass - see
    *   https://github.com/linkml/linkml/issues/1384#issuecomment-1892721142
    */
  def maximumValue: Option[LinkMlAny]

  /** The minimum number of entries for a multivalued slot
    *
    * @note
    *   Minimum_cardinality cannot be greater than maximum_cardinality
    */
  def minimumCardinality: Option[Int]

  /** For ordinal ranges, the value must be equal to or higher than this
    *
    * @see
    *   Aliases: low value
    * @note
    *   Range to be refined to an "Ordinal" metaclass - see
    *   https://github.com/linkml/linkml/issues/1384#issuecomment-1892721142
    */
  def minimumValue: Option[LinkMlAny]

  /** Defines the type of the object of the slot. Given the following slot definition S1: domain: C1
    * range: C2 the declaration X: S1: Y
    *
    * implicitly asserts Y is an instance of C2
    *
    * @see
    *   Aliases: value domain
    * @note
    *   Range is underspecified, as not all elements can appear as the range of a slot.
    * @note
    *   To use a URI or CURIE as the range, create a class with the URI or curie as the class_uri
    */
  def range: Option[Reference[Element]]

  /** A range that is described as a boolean expression combining existing ranges
    *
    * @note
    *   One use for this is being able to describe a range using any_of expressions, for example to
    *   combine two enums
    */
  def rangeExpression: Option[AnonymousClassExpressionImpl]

  /** The string value of the slot must conform to the regular expression in the pattern expression
    *
    * @see
    *   https://linkml.io/linkml/schemas/constraints.html#structured-patterns
    */
  def structuredPattern: Option[PatternExpressionImpl]

  /** An encoding of a unit
    */
  def unit: Option[UnitOfMeasureImpl]

  /** If PRESENT then a value must be present (for lists there must be at least one value). If
    * ABSENT then a value must be absent (for lists, must be empty)
    *
    * @note
    *   If set to true this has the same effect as required=true. In contrast, required=false allows
    *   a value to be present
    */
  def valuePresence: Option[Reference[PresenceEnum]]
}

package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[UnitOfMeasure]] LinkML class
  *
  * @inheritdoc
  */
case class UnitOfMeasureImpl(
    abbreviation: Option[String] = None,
    derivation: Option[String] = None,
    @named("descriptive_name")
    descriptiveName: Option[String] = None,
    @named("exact_mappings")
    exactMappings: Seq[UriOrCurie] = Seq(),
    @named("has_quantity_kind")
    hasQuantityKind: Option[UriOrCurie] = None,
    iec61360code: Option[String] = None,
    symbol: Option[String] = None,
    @named("ucum_code")
    ucumCode: Option[String] = None,
) extends UnitOfMeasure

/** A unit of measure, or unit, is a particular quantity value that has been chosen as a scale for
  * measuring other quantities the same kind (more generally of equivalent dimension).
  */
abstract class UnitOfMeasure {

  /** An abbreviation for a unit is a short ASCII string that is used in place of the full name for
    * the unit in contexts where non-ASCII characters would be problematic, or where using the
    * abbreviation will enhance readability. When a power of a base unit needs to be expressed, such
    * as squares this can be done using abbreviations rather than symbols (source: qudt)
    */
  def abbreviation: Option[String]

  /** Expression for deriving this unit from other units
    */
  def derivation: Option[String]

  /** The spelled out name of the unit, for example, meter
    */
  def descriptiveName: Option[String]

  /** Used to link a unit to equivalent concepts in ontologies such as UO, SNOMED, OEM, OBOE, NCIT
    *
    * @note
    *   Do not use this to encode mappings to systems for which a dedicated field exists
    */
  def exactMappings: Seq[UriOrCurie]

  /** Concept in a vocabulary or ontology that denotes the kind of quantity being measured, e.g.
    * length
    *
    * @note
    *   Potential ontologies include but are not limited to PATO, NCIT, OBOE, qudt.quantityKind
    */
  def hasQuantityKind: Option[UriOrCurie]

  def iec61360code: Option[String]

  /** Name of the unit encoded as a symbol
    */
  def symbol: Option[String]

  /** Associates a QUDT unit with its UCUM code (case-sensitive).
    */
  def ucumCode: Option[String]
}

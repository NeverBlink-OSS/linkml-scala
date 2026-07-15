package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** The formula used to generate the set of permissible values from the code_set values
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
sealed abstract class PvFormulaOptions

object PvFormulaOptions {

  /** The permissible values are the set of possible codes in the code set
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("CODE") case object Code extends PvFormulaOptions

  /** The permissible values are the set of CURIES in the code set
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("CURIE") case object Curie extends PvFormulaOptions

  /** The permissible values are the set of code URIs in the code set
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("URI") case object Uri extends PvFormulaOptions

  /** The permissible values are the set of FHIR coding elements derived from the code set
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("FHIR_CODING") case object FhirCoding extends PvFormulaOptions

  /** The permissible values are the set of human readable labels in the code set
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("LABEL") case object Label extends PvFormulaOptions
}

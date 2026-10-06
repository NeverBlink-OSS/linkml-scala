package eu.neverblink.linkml.generator.owl.config

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** How to import `C rdfs:subClassOf [ owl:someValuesFrom F ; owl:onProperty p ]`.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/owl-import-config
  */
sealed abstract class SomeValuesFrom derives Stringify

object SomeValuesFrom {

  /** Sets `required: true` on `p` in `C`. If `F` is narrower than the range of `p`, also adds
    * `has_member: {range: F}`, which matches the axiom exactly.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  @named("required") case object Required extends SomeValuesFrom

  /** Sets `required: true` and `range: F`. This is stricter than the axiom, since every value must
    * be an `F`, not just one.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  @named("range") case object Range extends SomeValuesFrom
}

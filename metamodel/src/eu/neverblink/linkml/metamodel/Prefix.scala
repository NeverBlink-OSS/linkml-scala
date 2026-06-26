package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[Prefix]] LinkML class
  *
  * @inheritdoc
  */
case class PrefixImpl(
    @id
    @named("prefix_prefix")
    prefixPrefix: String,
    @value
    @named("prefix_reference")
    prefixReference: UriOrCurie,
) extends Prefix

/** Prefix URI tuple
  */
abstract class Prefix {

  /** The prefix components of a prefix expansions. This is the part that appears before the colon
    * in a CURIE.
    */
  def prefixPrefix: String

  /** The namespace to which a prefix expands to.
    */
  def prefixReference: UriOrCurie
}

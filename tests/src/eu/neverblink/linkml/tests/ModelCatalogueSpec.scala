package eu.neverblink.linkml.tests

import eu.neverblink.linkml.tests.ModelCatalogue.{Entry, InstanceInFormats}
import org.scalatest.wordspec.AnyWordSpecLike

/** Mixin trait allowing easier usage of [[ModelCatalogue]] for model/instance skipping. Useful for
  * writing tests interacting with instances
  */
trait ModelCatalogueSpec {
  this: AnyWordSpecLike =>

  /** Map of "model name" -> "disable reason". If a key is present, then [[processSkip]] will
    * indicate that tests for this model should be canceled.
    */
  def skipModels: Map[String, String] = Map.empty

  /** Map of "model name" -> "instance name" -> "disable reason". If a key is present, then
    * [[processSkip]] will indicate that tests for this model should be canceled.
    */
  def skipInstances: Map[(String, String), String] = Map.empty

  /** Check whether this test should be run as per [[skipModels]] and [[skipInstances]]
    * @param entry
    *   Model that is being tested
    * @param instance
    *   Instance that is being tested
    * @throws org.scalatest.exceptions.TestCanceledException
    *   when the test should be cancelled
    */
  final def processSkip(entry: Entry, instance: InstanceInFormats): Unit = {
    val name = entry.model.root.name
    assume(!skipModels.contains(name), skipModels.getOrElse(name, ""))
    assume(
      !skipInstances.contains((name, instance.name)),
      skipInstances.getOrElse((name, instance.name), ""),
    )
  }
}

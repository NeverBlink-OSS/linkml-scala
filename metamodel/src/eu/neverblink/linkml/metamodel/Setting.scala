package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[Setting]] LinkML class
  *
  * @inheritdoc
  */
final case class SettingImpl(
    @id
    @named("setting_key")
    settingKey: NcName,
    @value
    @named("setting_value")
    settingValue: String,
) extends Setting {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * Only single-valued slots with a `string` range are inferred; slots with any other range are
    * left untouched.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): SettingImpl =
    this
}

/** Assignment of a key to a value
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class Setting {

  /** The variable name for a setting
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def settingKey: NcName

  /** The value assigned for a setting
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def settingValue: String

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): Setting
}

package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[Setting]] LinkML class
  *
  * @inheritdoc
  */
case class SettingImpl(
    @id
    @named("setting_key")
    settingKey: NcName,
    @value
    @named("setting_value")
    settingValue: String,
) extends Setting

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
}

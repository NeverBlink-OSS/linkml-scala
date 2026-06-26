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
    settingKey: String,
    @value
    @named("setting_value")
    settingValue: String,
) extends Setting

/** Assignment of a key to a value
  */
abstract class Setting {

  /** The variable name for a setting
    */
  def settingKey: String

  /** The value assigned for a setting
    */
  def settingValue: String
}

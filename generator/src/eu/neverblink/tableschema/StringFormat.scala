package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*


sealed abstract class StringFormat

object StringFormat {
  /** Any valid string.
    */
  @named("default") case object Default extends StringFormat
  /** A valid email address.
    */
  @named("email") case object Email extends StringFormat
  /** A valid URI.
    */
  @named("uri") case object Uri extends StringFormat
  /** A base64 encoded string representing binary data.
    */
  @named("binary") case object Binary extends StringFormat
  /** A string that is a uuid.
    */
  @named("uuid") case object Uuid extends StringFormat
}

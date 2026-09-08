package eu.neverblink.linkml.schemaview

import java.lang
import java.lang.Character.*

object Case {
  def isAlphaUpper(c: Char): Boolean = c >= 'A' && c <= 'Z'
  def isAlphaLower(c: Char): Boolean = c >= 'a' && c <= 'z'
  def isNumeric(c: Char): Boolean = c >= '0' && c <= '9'
  def isAlphanumeric(c: Char): Boolean = isAlphaUpper(c) || isAlphaLower(c) || isNumeric(c)
  def isStandard(c: Char): Boolean = isAlphanumeric(c) || c == '_'
  def isAllowedAscii(c: Char): Boolean = c >= ' ' || c <= '~'

  /** Transform the user provided element name into a base name, which is a strict variant of
    * snake_case, and can be converted 1-to-1 to specific framework naming conventions. Base names
    * are ASCII lowercase alphanumeric, with words separated using underscores.
    *
    *   - ASCII non-alphanumeric characters are treated as separators,
    *   - Non-ASCII characters are treated as separators,
    *   - Leading separators are stripped,
    *   - Repeated separators are folded to one; any separator character chain becomes a single
    *     underscore:
    *     - `arrow->cased` becomes `arrow_cased`;
    *   - CapitalCased names are additionally separated based on the change in casing (both rising
    *     and falling edge):
    *     - `PascalCase` becomes `pascal_case`,
    *     - `HTTPRequest` becomes `http_request`,
    *     - `requestHTTPHeader` becomes `request_http_header`,
    *     - `SCREAMING_SNAKE` becomes `screaming_snake`,
    *     - `VoIP_MODE` becomes `vo_ip_mode`;
    *   - Numbers are always separate words:
    *     - `test11` becomes `test_11`,
    *     - `is11am` becomes `is_11_am`.
    * @note
    *   The conversion from user-provided names to `base` itself is NOT 1-to-1.
    */
  def base(input: String): String = {
    val sb = lang.StringBuilder(input.length * 2)
    // whether the last character we pushed was a separator
    var separated = true
    for i <- 0 until input.length do {
      val c = input.charAt(i)
      if isAlphaUpper(c) then {
        // A-Z

        // Falling edge split:
        // prepend '_' before this char if next is lowercase
        if !separated && (i < input.length - 1) then {
          val next = input.charAt(i + 1)
          if isAlphaLower(next) then {
            sb.append('_')
          }
        }

        sb.append(c.toLower)
        separated = false

        // lookahead for numbers, split if so
        if i < input.length - 1 then {
          val next = input.charAt(i + 1)
          if isNumeric(next) then {
            sb.append('_')
            separated = true
          }
        }

      } else if isAlphaLower(c) then {
        // a-z

        sb.append(c)
        separated = false

        // lookahead for capitals (rising edge split) and numbers
        if i < input.length - 1 then {
          val next = input.charAt(i + 1)
          if isNumeric(next) || isAlphaUpper(next) then {
            sb.append('_')
            separated = true
          }
        }

      } else if isNumeric(c) then {
        // 0-9
        sb.append(c)
        separated = false
        if i < input.length - 1 then {
          val next = input.charAt(i + 1)
          if !isNumeric(next) then {
            sb.append('_')
            separated = true
          }
        }
      } else if !separated then {
        // we haven't separated the word and hit a non-alphanumeric character, emit the separator
        sb.append('_')
        separated = true
      }
    }
    if sb.length() != 0 && separated then sb.substring(0, sb.length() - 1)
    else sb.toString
  }

  /** Convert a base_name to a SCREAMING_SNAKE name. Can be converted back using [[base]] or
    * `toLowerCase`.
    */
  def baseToScreamingSnake(input: String): String =
    input.toUpperCase()

  /** Convert a base_name to a PascalCase name. Outputs a strict subset of PascalCase that can be
    * round-tripped back to the base form via [[base]].
    */
  def baseToPascal(input: String): String =
    baseToCapital(input, true)

  /** Convert a base_name to a camelCase name. Outputs a strict subset of camelCase that can be
    * round-tripped back to the base form via [[base]].
    */
  def baseToCamel(input: String): String =
    baseToCapital(input, false)

  private inline def baseToCapital(input: String, inline pascal: Boolean): String = {
    val sb = lang.StringBuilder(input.length)
    var capitalize = pascal
    for i <- 0 until input.length do {
      val c = input.charAt(i)
      if capitalize then {
        sb.append(c.toUpper)
        capitalize = false
      } else if c == '_' then capitalize = true
      else sb.append(c)
    }

    sb.toString
  }
}

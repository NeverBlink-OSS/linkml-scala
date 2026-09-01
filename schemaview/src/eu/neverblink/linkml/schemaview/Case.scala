package eu.neverblink.linkml.schemaview

import java.lang
import java.lang.Character.*

object Case {
  def isAlphaUpper(c: Char): Boolean = c >= 'A' && c <= 'Z'
  def isAlphaLower(c: Char): Boolean = c >= 'a' && c <= 'z'
  def isNumeric(c: Char): Boolean = c >= '0' && c <= '9'
  def isAlphanumeric(c: Char): Boolean = isAlphaUpper(c) || isAlphaLower(c) || isNumeric(c)
  
  /** Transform the user provided element name into an internal name, which can be transformed
    * 1-to-1 to specific framework naming conventions. Internal names are ASCII lowercase
    * alphanumeric, with words separated using underscores.
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
    *     - HTTPRequest becomes `http_request`,
    *     - `requestHTTPHeader` becomes `request_http_header`,
    *     - `SCREAMING_SNAKE` becomes `screaming_snake`,
    *     - `VoIP_MODE` becomes `vo_ip_mode`;
    *   - Numbers are always separate words:
    *     - `test11` becomes `test_11`,
    *     - `is11am` becomes `is_11_am`.
    * @note
    *   The conversion from user-provided to `base`` itself is NOT 1-to-1.
    */
  def base(input: String): String = {
    val sb = lang.StringBuilder(input.length * 2)
    // whether the last character we pushed was a separator
    var separated = true
    for i <- 0 until input.length do {
      val c = input.charAt(i)
      if isAlphaUpper(c) then {
        // falling edge split to make HTTPHandler split correctly
        if !separated && (i < input.length - 1) then {
          val next = input.charAt(i + 1)
          if isAlphaLower(next) then {
            sb.append('_')
          }
        }
        sb.append(c.toLower)
        separated = false
        // number separation
        if i < input.length - 1 then {
          val next = input.charAt(i + 1)
          if isNumeric(next) then {
            sb.append('_')
            separated = true
          }
        }
      } else if isAlphaLower(c) then {
        sb.append(c)
        separated = false

        if i < input.length - 1 then {
          val next = input.charAt(i + 1)
          // number separation
          if isNumeric(next) then {
            sb.append('_')
            separated = true
          }
          // lookahead to convert CapitalCases to capital_cases
          if isAlphaUpper(next) then {
            sb.append('_')
            separated = true
          }
        }
      } else if isNumeric(c) then {
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
      // no match = nothing to emit
    }
    if sb.length() != 0 && separated then sb.substring(0, sb.length() - 1)
    else sb.toString
  }

  def baseToCamel(input: String, pascal: Boolean): String = {
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

  /** Enforces the name format as described by regex `[A-Za-z_][A-Za-z0-9_]*`, escaping other
    * characters with underscores `_`. Leading digits are escaped by prepending the string with an
    * underscore.
    */
  def escaped(name: String): String = {
    val res = name.map { c =>
      if c >= 'A' && c <= 'Z' then c
      else if c >= 'a' && c <= 'z' then c
      else if c >= '0' && c <= '9' then c
      else '_'
    }
    if res.head >= '0' && res.head <= '9' then res.prepended('_')
    else res
  }

  /** Enforces snake_case format.
    *
    * It accepts names in any of 5 cases: camelCase, PascalCase, snake_case, kebab-case, space case.
    */
  def snake_case(name: String): String = snakeOrKebabOrSpaceCase(name, '_')

  /** Enforces PascalCase format.
    *
    * It accepts names in any of 5 cases: camelCase, PascalCase, snake_case, kebab-case, space case.
    */
  def PascalCase(name: String): String = camelOrPascalCase(name, true)

  /** Enforces camelCase format.
    *
    * It accepts names in any of 5 cases: camelCase, PascalCase, snake_case, kebab-case, space case.
    */
  def camelCase(name: String): String = camelOrPascalCase(name, false)

  /** Enforces snake- or kebab- or space cases with joined non-alphabetic characters.
    *
    * @param name
    *   the input string
    * @param separator
    *   the separator character: '_' for snake case, '-' for kebab case, or ' ' for space case
    * @return
    *   the input string reformatted to case selected by the separator parameter
    */
  private def snakeOrKebabOrSpaceCase(name: String, separator: Char): String = {
    val len = name.length
    val sb = new java.lang.StringBuilder(len << 1)
    var i = 0
    var isPrecedingNotUpperCased = false
    while (i < len) isPrecedingNotUpperCased = {
      val ch = name.charAt(i)
      i += 1
      if (ch == '_' || ch == '-' || ch == ' ') {
        if (i > 1 && i < len && !isAlphabetic(name.charAt(i))) isPrecedingNotUpperCased
        else {
          sb.append(separator)
          false
        }
      } else if (!isUpperCase(ch)) {
        sb.append(ch)
        true
      } else {
        if (isPrecedingNotUpperCased || i > 1 && i < len && isLowerCase(name.charAt(i))) {
          sb.append(separator)
        }
        sb.append(toLowerCase(ch))
        false
      }
    }
    sb.toString
  }

  /** Enforces camel- or pascal- cases.
    * @param name
    *   the input string
    * @param toPascal
    *   the flag to enforce pascal case when true or camel case when false
    * @return
    *   the input string formatted to case selected by the toPascal parameter
    */
  private def camelOrPascalCase(name: String, toPascal: Boolean): String = {
    val len = name.length
    val sb = new java.lang.StringBuilder(len)
    if (name.indexOf('_') < 0 && name.indexOf('-') < 0 && name.indexOf(' ') < 0) {
      val len = name.length
      if (len > 0) {
        val firstChar = name.charAt(0)
        sb.append({
          if (toPascal) toUpperCase(firstChar)
          else toLowerCase(firstChar)
        })
        var i = 0
        while (i < len && isUpperCase(name.charAt(i))) i += 1
        if (i > 1 && i < len && isLowerCase(name.charAt(i))) i -= 1
        val limit = Math.max(i, 1)
        i = 1
        while (i < limit) {
          sb.append(toLowerCase(name.charAt(i)))
          i += 1
        }
        while (i < len) {
          sb.append(name.charAt(i))
          i += 1
        }
      }
    } else {
      var i = 0
      var isPrecedingDash = toPascal
      while (i < len) isPrecedingDash = {
        val ch = name.charAt(i)
        i += 1
        (ch == '_' || ch == '-' || ch == ' ') || {
          val fixedCh =
            if (isPrecedingDash) toUpperCase(ch)
            else toLowerCase(ch)
          sb.append(fixedCh)
          false
        }
      }
    }
    sb.toString
  }
}

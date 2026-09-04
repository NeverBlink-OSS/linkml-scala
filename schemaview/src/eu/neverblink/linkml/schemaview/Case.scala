package eu.neverblink.linkml.schemaview

import java.lang.Character._

object Case {

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
    while (i < len) {
      val ch = name.charAt(i)
      i += 1
      if (ch == '_' || ch == '-' || ch == ' ') {
        if (i <= 1 || i >= len || isAlphabetic(name.charAt(i))) {
          sb.append(separator)
          isPrecedingNotUpperCased = false
        }
      } else if (!isUpperCase(ch)) {
        sb.append(ch)
        isPrecedingNotUpperCased = true
      } else {
        if (isPrecedingNotUpperCased || i > 1 && i < len && isLowerCase(name.charAt(i))) {
          sb.append(separator)
        }
        sb.append(toLowerCase(ch))
        isPrecedingNotUpperCased = false
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
      while (i < len) {
        val ch = name.charAt(i)
        i += 1
        if (ch == '_' || ch == '-' || ch == ' ') isPrecedingDash = true
        else {
          val fixedCh =
            if (isPrecedingDash) toUpperCase(ch)
            else toLowerCase(ch)
          sb.append(fixedCh)
          isPrecedingDash = false
        }
      }
    }
    sb.toString
  }
}

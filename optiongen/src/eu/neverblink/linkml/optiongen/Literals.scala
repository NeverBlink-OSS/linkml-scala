package eu.neverblink.linkml.optiongen

object Literals {
  // ScalaWords.keywords plus `forSome`, `_` and the soft keyword `using`, which dotc rejects as the
  // first name of a parameter clause. Kept local to avoid a generator dependency.
  private val scalaKeywords = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "final",
    "finally",
    "for",
    "forSome",
    "given",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "this",
    "throw",
    "trait",
    "true",
    "try",
    "type",
    "using",
    "val",
    "var",
    "while",
    "with",
    "yield",
    "_",
  )

  // keyword.kwlist for Python 3.10 and later. Python 3.9 adds `__peg_parser__`, which is excluded.
  // Soft keywords are legal identifiers and are not listed.
  private[optiongen] val pythonKeywords: Set[String] = Set(
    "False",
    "None",
    "True",
    "and",
    "as",
    "assert",
    "async",
    "await",
    "break",
    "class",
    "continue",
    "def",
    "del",
    "elif",
    "else",
    "except",
    "finally",
    "for",
    "from",
    "global",
    "if",
    "import",
    "in",
    "is",
    "lambda",
    "nonlocal",
    "not",
    "or",
    "pass",
    "raise",
    "return",
    "try",
    "while",
    "with",
    "yield",
  )

  // Names the Python bindings reserve. `self` is the receiver, `function` is the first parameter of
  // `Schema._document` and `Schema._json` in python/linkml_scala/__init__.py, and `_pruning` is the
  // module helper the generated methods call.
  private[optiongen] val pythonFixedNames: Set[String] = Set("self", "function", "_pruning")

  // Parameter names a strict TypeScript declaration file rejects, checked with TypeScript 5.9.3 and
  // 7.0.2. The reserved words fail with TS1390. `this` is parsed as a receiver type annotation, so
  // it fails with TS2680 after the schema parameter and never denotes an argument position.
  // Strict-mode words such as `package` and `yield` compile and are not listed.
  private[optiongen] val tsReservedWords: Set[String] = Set(
    "break",
    "case",
    "catch",
    "class",
    "const",
    "continue",
    "debugger",
    "default",
    "delete",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "finally",
    "for",
    "function",
    "if",
    "import",
    "in",
    "instanceof",
    "new",
    "null",
    "return",
    "super",
    "switch",
    "throw",
    "true",
    "try",
    "typeof",
    "var",
    "void",
    "while",
    "with",
    "this",
  )

  // Names a case-class field cannot carry, checked by compiling
  // `final case class C(<name>: Int = 7, other: String = "o")` with Scala 3.9.0. `notify`,
  // `notifyAll` and `wait` fail as overrides of final members of `Object`, the other seven compile
  // failures are missing `override` modifiers against `Any`, `Object` or `Product`. `productArity`
  // compiles and makes `toString` and `productElementNames` throw at runtime. `getClass`, `equals`,
  // `copy`, `canEqual`, `productElement` and the remaining inherited names compile as overloads and
  // keep every case-class operation working, so they stay accepted. A parenless `options.getClass`
  // would then select the field, which no production caller does.
  private[optiongen] val scalaMemberNames: Set[String] = Set(
    "hashCode",
    "toString",
    "notify",
    "notifyAll",
    "wait",
    "clone",
    "finalize",
    "productPrefix",
    "productIterator",
    "productElementNames",
    "productArity",
  )

  // Names a generated JS facade body references unqualified, which are the generator's own
  // companion, `PruningMode` and the `js` package. Generators outside the registry reserve the last
  // two.
  private[optiongen] def jsWrapperNames(id: String): Set[String] =
    Entrypoints.all.find(_.python == id).map(_.generator).toSet + "PruningMode" + "js"

  def scalaIdentifier(value: String): String = {
    require(value.matches("[A-Za-z_][A-Za-z0-9_]*"), s"Unsupported Scala identifier '$value'")
    if scalaKeywords(value) then s"`$value`" else value
  }

  def quote(value: String): String = {
    val escaped = value.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if Character.isISOControl(c) => f"\\u${c.toInt}%04x"
      case c => c.toString
    }
    s"\"$escaped\""
  }

  def commentLines(value: String): Vector[String] =
    value.replace("\\", "&#92;").replace("/*", "/ *").replace("*/", "* /")
      .replace("\r\n", "\n").replace('\r', '\n').split("\n", -1).toVector
}

package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.generator.util.CharSink

import scala.compiletime.uninitialized

/** Streaming Turtle serializer. Prettification is done on a best-effort basis:
  *
  * What is supported:
  *   - `PREFIX` and `BASE` directives, then a prefixed name for every IRI whose namespace was
  *     declared and whose local part needs no escaping. BASE is never used for shortening.
  *   - `a` for `rdf:type` in the predicate position.
  *   - `;` when the triple repeats the previous subject, `,` when it repeats the subject and the
  *     predicate.
  *   - `[ ... ]` for [[InlineBlankNode]] objects, nested to any depth.
  *   - `( ... )` for the RDF lists pushed through [[list]].
  *   - Bare `1`, `-2.5`, `1.0e6` and `true` for XSD literals whose lexical form is already a Turtle
  *     number or boolean token, and `"""..."""` for strings containing newlines.
  *
  * Unlike [[NTriplesWriter]], characters outside ASCII are written as themselves rather than
  * escaped.
  *
  * Single-use and not thread-safe. Call [[finish]] exactly once at the end - the last statement has
  * no terminating `.` until you do.
  */
final class TurtleWriter(out: CharSink) extends RdfSink {
  import TurtleWriter.*

  /** Declared prefixes, in declaration order.
    */
  private val prefixes = scala.collection.mutable.LinkedHashMap.empty[String, String]
  private var baseIri: String = uninitialized

  /** Namespaces to try when shortening an IRI, longest first, with [[shortenPrefixes]] holding the
    * matching prefix at the same index. Built from [[prefixes]] by [[begin]].
    */
  private var shortenNamespaces: Array[String] = Array.empty
  private var shortenPrefixes: Array[String] = Array.empty

  /** Subjects whose predicate-object list is still open: `frames(0)` is the statement's own
    * subject, and every frame above it is an inline blank node waiting for its `]`. Frames are
    * reused across statements, so [[depth]] rather than the array length says how many are live.
    */
  private var frames = new Array[Frame](8)
  private var depth = 0

  private var wroteDirectives = false
  private var hadDirectives = false
  private var wroteStatement = false

  def namespace(prefix: String, name: String): Unit = {
    requireNotStarted("namespace")
    // A prefix that cannot be written as a PN_PREFIX cannot be declared either, so drop it and let
    // its IRIs be written out in full rather than emit a directive that will not parse.
    if (isPnPrefix(prefix)) prefixes.put(prefix, name)
  }

  override def base(iri: String): Unit = {
    requireNotStarted("base")
    baseIri = iri
  }

  def triple(subj: Resource, pred: Iri, obj: Node): Unit = {
    position(subj, pred)
    writeObject(obj)
  }

  override def list(subj: Resource, pred: Iri, values: Seq[Node]): Unit = {
    position(subj, pred)
    if (values.isEmpty) {
      out.append("()")
      return
    }
    out.appendAscii('(')
    values.foreach { value =>
      // A collection is written in full the moment it arrives, so there is no window in which an
      // inline blank node's own triples could be pushed. Its contents would be silently dropped.
      if (value.isInstanceOf[InlineBlankNode])
        throw new IllegalArgumentException(
          "An InlineBlankNode cannot be a member of an RDF list - use a plain BlankNode",
        )
      out.appendAscii(' ')
      writeObject(value)
    }
    out.append(" )")
  }

  override def finish(): Unit = {
    begin()
    closeStatement()
  }

  /** Write everything up to and including the space that precedes the object: the subject and
    * predicate for a new statement, or just the `;` / `,` continuation for one already open.
    */
  private def position(subj: Resource, pred: Iri): Unit = {
    begin()
    val frame = openFrame(subj)
    if (frame < 0) {
      subj match {
        case node: InlineBlankNode =>
          throw new IllegalStateException(
            s"Inline blank node _:${node.id} is not open. Its triples " +
              "must directly follow the triple that references it, with no other subject in between.",
          )
        case _ =>
      }
      closeStatement()
      // A blank line off the directives and between statements.
      if (wroteStatement || hadDirectives) out.appendAscii('\n')
      wroteStatement = true
      writeResource(subj)
      push(subj)
      out.appendAscii(' ')
      writePredicate(pred)
      frames(depth - 1).pred = pred
    } else {
      closeAbove(frame)
      val f = frames(frame)
      if (f.pred != null && sameIri(f.pred, pred)) out.append(" ,")
      else {
        if (f.pred != null) out.append(" ;")
        newlineIndent(frame + 1)
        writePredicate(pred)
        f.pred = pred
      }
    }
    out.appendAscii(' ')
  }

  /** Emit the directives, once, ahead of the first statement. We use the RDF 1.1 / SPARQL style of
    * directives.
    */
  private def begin(): Unit =
    if (!wroteDirectives) {
      wroteDirectives = true
      if (baseIri != null) {
        out.append("BASE ")
        writeIriRef(baseIri)
        out.appendAscii('\n')
      }
      shortenNamespaces = new Array[String](prefixes.size)
      shortenPrefixes = new Array[String](prefixes.size)
      var i = 0
      prefixes.foreach { (prefix, name) =>
        out.append("PREFIX ")
        out.append(prefix)
        out.append(": ")
        writeIriRef(name)
        out.appendAscii('\n')
        shortenNamespaces(i) = name
        shortenPrefixes(i) = prefix
        i += 1
      }
      sortByNamespaceLength()
      hadDirectives = baseIri != null || prefixes.nonEmpty
    }

  /** Order the shortening table longest namespace first, so that the most specific declaration wins
    * when one namespace is a prefix of another. Insertion sort: there are only ever a handful.
    */
  private def sortByNamespaceLength(): Unit = {
    var i = 1
    while (i < shortenNamespaces.length) {
      val name = shortenNamespaces(i)
      val prefix = shortenPrefixes(i)
      var j = i - 1
      while (j >= 0 && shortenNamespaces(j).length < name.length) {
        shortenNamespaces(j + 1) = shortenNamespaces(j)
        shortenPrefixes(j + 1) = shortenPrefixes(j)
        j -= 1
      }
      shortenNamespaces(j + 1) = name
      shortenPrefixes(j + 1) = prefix
      i += 1
    }
  }

  private def requireNotStarted(what: String): Unit =
    if (wroteDirectives)
      throw new IllegalStateException(
        s"Cannot declare a $what after the first triple: Turtle directives come first",
      )

  // Open frames

  /** The index of the open frame `subj` belongs to, or -1 if there is none. */
  private def openFrame(subj: Resource): Int = {
    var i = depth - 1
    while (i >= 0) {
      if (sameResource(frames(i).subj, subj)) return i
      i -= 1
    }
    -1
  }

  private def push(subj: Resource): Unit = {
    if (depth == frames.length) {
      val grown = new Array[Frame](depth * 2)
      System.arraycopy(frames, 0, grown, 0, depth)
      frames = grown
    }
    var f = frames(depth)
    if (f == null) {
      f = new Frame
      frames(depth) = f
    }
    f.subj = subj
    f.pred = null
    depth += 1
  }

  /** Close every inline blank node opened inside the frame at `index`. */
  private def closeAbove(index: Int): Unit =
    while (depth > index + 1) {
      depth -= 1
      val f = frames(depth)
      // A frame that never took a predicate holds nothing, so `[` and `]` collapse to `[]`.
      if (f.pred != null) newlineIndent(depth)
      out.appendAscii(']')
    }

  /** Terminate the statement in progress, if any. */
  private def closeStatement(): Unit =
    if (depth > 0) {
      closeAbove(0)
      depth = 0
      out.append(" .\n")
    }

  private def newlineIndent(level: Int): Unit = {
    out.appendAscii('\n')
    var i = level * 2
    while (i > 0) {
      out.appendAscii(' ')
      i -= 1
    }
  }

  // Terms

  private def writeObject(obj: Node): Unit = obj match {
    case inline: InlineBlankNode =>
      if (openFrame(inline) >= 0)
        throw new IllegalStateException(
          s"Inline blank node _:${inline.id} is referenced twice: it may have only one reference",
        )
      out.appendAscii('[')
      push(inline)
    case res: Resource => writeResource(res)
    case lit: Literal => writeLiteral(lit)
    case lit: LanguageLiteral =>
      writeQuoted(lit.value)
      out.appendAscii('@')
      out.append(lit.language)
  }

  private def writeResource(res: Resource): Unit = res match {
    case iri: Iri => writeIri(iri)
    case b: AnyBlankNode =>
      out.append("_:")
      out.append(b.id)
  }

  private def writePredicate(pred: Iri): Unit =
    if (sameIri(pred, Rdf.`type`)) out.appendAscii('a') else writeIri(pred)

  private def writeIri(iri: Iri): Unit = {
    val value = iri.value
    val i = namespaceFor(value)
    if (i < 0) writeIriRef(value)
    else {
      out.append(shortenPrefixes(i))
      out.appendAscii(':')
      TurtleEscape.appendAsciiRange(out, value, shortenNamespaces(i).length)
    }
  }

  private def writeIriRef(value: String): Unit = {
    out.appendAscii('<')
    TurtleEscape.escapeIri(out, value)
    out.appendAscii('>')
  }

  /** Find the longest namespace that can be used to shorten `value` to a prefixed name, or -1 if
    * none.
    */
  private def namespaceFor(value: String): Int = {
    var i = 0
    while (i < shortenNamespaces.length) {
      if (fits(value, i)) return i
      i += 1
    }
    -1
  }

  private def fits(value: String, i: Int): Boolean = {
    val name = shortenNamespaces(i)
    value.startsWith(name) && isPnLocal(value, name.length)
  }

  private def writeLiteral(lit: Literal): Unit = {
    val datatype = lit.datatype
    val value = lit.value
    if ((datatype eq XmlSchema.string) || isXsd(datatype, "string")) writeQuoted(value)
    else if (isXsdShorthand(datatype, value)) out.append(value)
    else {
      writeQuoted(value)
      out.append("^^")
      writeIri(datatype)
    }
  }

  /** Whether `value` can be written bare, because it is an XSD number or boolean whose lexical form
    * is already the Turtle token for it. Anything else has to keep its quotes and datatype.
    */
  private def isXsdShorthand(datatype: Iri, value: String): Boolean = {
    val iri = datatype.value
    // One namespace comparison, then only the short local name per candidate.
    if (!iri.startsWith(XsdNamespace)) return false
    val local = XsdNamespace.length
    if (isLocalName(iri, local, "integer")) isTurtleInteger(value)
    else if (isLocalName(iri, local, "decimal")) isTurtleDecimal(value)
    else if (isLocalName(iri, local, "double")) isTurtleDouble(value)
    else if (isLocalName(iri, local, "boolean")) value == "true" || value == "false"
    else false
  }

  /** Write a lexical form as a quoted string, using the `"""` form when it spans lines. */
  private def writeQuoted(value: String): Unit =
    if (value.indexOf('\n') >= 0) {
      out.append("\"\"\"")
      TurtleEscape.escapeLongString(out, value)
      out.append("\"\"\"")
    } else {
      out.appendAscii('"')
      TurtleEscape.escapeString(out, value)
      out.appendAscii('"')
    }
}

object TurtleWriter {

  /** One open predicate-object list.
    */
  private final class Frame {
    var subj: Resource = uninitialized

    /** The last predicate written for [[subj]], or null before the first. */
    var pred: Iri = uninitialized
  }

  private val XsdNamespace = "http://www.w3.org/2001/XMLSchema#"

  private def sameIri(a: Iri, b: Iri): Boolean = (a eq b) || a.value == b.value

  private def sameResource(a: Resource, b: Resource): Boolean = (a eq b) || ((a, b) match {
    case (x: Iri, y: Iri) => x.value == y.value
    case (x: BlankNode, y: BlankNode) => x.id == y.id
    case (x: InlineBlankNode, y: InlineBlankNode) => x.id == y.id
    case _ => false
  })

  /** Whether `datatype` is the XSD type named `local`, without taking the IRI apart. */
  private def isXsd(datatype: Iri, local: String): Boolean = {
    val value = datatype.value
    value.startsWith(XsdNamespace) && isLocalName(value, XsdNamespace.length, local)
  }

  /** Whether `iri` from `from` onwards is exactly `local`. */
  private def isLocalName(iri: String, from: Int, local: String): Boolean =
    iri.length == from + local.length && iri.startsWith(local, from)

  /** Whether `prefix` can be written before the `:` of a prefixed name, i.e. matches PN_PREFIX.
    * Only ASCII is accepted.
    */
  private def isPnPrefix(prefix: String): Boolean = {
    val len = prefix.length
    if (len == 0) return true // the default prefix, `PREFIX : <...>`
    if (!isAsciiLetter(prefix.charAt(0))) return false
    if (prefix.charAt(len - 1) == '.') return false
    var i = 1
    while (i < len) {
      val c = prefix.charAt(i)
      if (!isAsciiLetter(c) && !isAsciiDigit(c) && c != '_' && c != '-' && c != '.') return false
      i += 1
    }
    true
  }

  /** Whether `value.substring(from)` can be written after the `:` of a prefixed name, i.e. matches
    * PN_LOCAL. Conservative in the same way as [[isPnPrefix]], and additionally refuses the
    * percent- and backslash-escapes.
    */
  private def isPnLocal(value: String, from: Int): Boolean = {
    val len = value.length
    if (from == len) return true // `prefix:` on its own names the namespace
    val first = value.charAt(from)
    if (!isAsciiLetter(first) && !isAsciiDigit(first) && first != '_' && first != ':') return false
    if (value.charAt(len - 1) == '.') return false
    var i = from + 1
    while (i < len) {
      val c = value.charAt(i)
      if (
        !isAsciiLetter(c) && !isAsciiDigit(c) &&
        c != '_' && c != '-' && c != '.' && c != ':'
      ) return false
      i += 1
    }
    true
  }

  /** Whether `value` is already a Turtle INTEGER: `[+-]? [0-9]+`. */
  private def isTurtleInteger(value: String): Boolean = {
    val start = afterSign(value, 0)
    digitRun(value, start) == value.length && value.length > start
  }

  /** Whether `value` is already a Turtle DECIMAL: `[+-]? [0-9]* '.' [0-9]+`. The dot is required,
    * so a whole number tagged `xsd:decimal` stays quoted rather than turning into an `xsd:integer`.
    */
  private def isTurtleDecimal(value: String): Boolean = {
    val start = afterSign(value, 0)
    val dot = digitRun(value, start)
    if (dot == value.length || value.charAt(dot) != '.') return false
    val end = digitRun(value, dot + 1)
    end == value.length && end > dot + 1
  }

  /** Whether `value` is already a Turtle DOUBLE, i.e. a decimal or integer mantissa followed by
    * `[eE] [+-]? [0-9]+`. Without the exponent it would read back as a decimal or an integer.
    */
  private def isTurtleDouble(value: String): Boolean = {
    val start = afterSign(value, 0)
    var i = digitRun(value, start)
    val intDigits = i - start
    var fracDigits = 0
    if (i < value.length && value.charAt(i) == '.') {
      val afterDot = digitRun(value, i + 1)
      fracDigits = afterDot - (i + 1)
      i = afterDot
    }
    if (intDigits == 0 && fracDigits == 0) return false
    if (i == value.length) return false
    val e = value.charAt(i)
    if (e != 'e' && e != 'E') return false
    val expStart = afterSign(value, i + 1)
    val end = digitRun(value, expStart)
    end == value.length && end > expStart
  }

  /** The index just past the optional `+` or `-` at `from`. */
  private def afterSign(value: String, from: Int): Int =
    if (from < value.length && (value.charAt(from) == '+' || value.charAt(from) == '-')) from + 1
    else from

  /** The index just past the run of digits starting at `from`. */
  private def digitRun(value: String, from: Int): Int = {
    var i = from
    while (i < value.length && isAsciiDigit(value.charAt(i))) i += 1
    i
  }

  private def isAsciiLetter(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

  private def isAsciiDigit(c: Char): Boolean = c >= '0' && c <= '9'
}

/** Turtle escaping. Unlike the N-Triples equivalent this leaves everything above ASCII unescaped,
  * as Turtle is intended for human consumption. Only what the grammar forbids is escaped, always
  * with an ASCII-only escape.
  */
private object TurtleEscape {
  import NTriplesEscape.{StringSafe, IriSafe}

  private val Hex: Array[Char] = "0123456789ABCDEF".toCharArray

  /** As [[StringSafe]], but for `"""..."""` literals, where a line feed is the whole point of using
    * the form and is written as itself.
    */
  private val LongStringSafe: Array[Boolean] = {
    val a = java.util.Arrays.copyOf(StringSafe, StringSafe.length)
    a('\n') = true
    a
  }

  /** Escape the lexical form of a single-quoted string literal. */
  def escapeString(sink: CharSink, s: String): Unit = escape(sink, s, StringSafe)

  /** Escape the lexical form of a `"""`-quoted string literal.
    *
    * Every `"` is escaped rather than only the ones that would close the literal early.
    */
  def escapeLongString(sink: CharSink, s: String): Unit = escape(sink, s, LongStringSafe)

  /** Escape the content of an IRI (the text between the angle brackets).
    *
    * This cannot share the loop below. An IRIREF admits only UCHAR - the `\\uXXXX` form - so the
    * `\\"` and `\\n` shorthands a string literal would use are a syntax error here, and `"`, `\\`
    * and the whitespace characters are exactly what an IRI is most likely to contain.
    */
  def escapeIri(sink: CharSink, s: String): Unit = {
    val safe = IriSafe
    val len = s.length
    var i = 0
    while (i < len) {
      val c = s.charAt(i)
      if (c >= 0x80) sink.append(c)
      else if (safe(c)) sink.appendAscii(c)
      else appendUnicodeEscape(sink, c)
      i += 1
    }
  }

  /** Append `s` from `from` onwards, which the caller has established is pure printable ASCII. */
  def appendAsciiRange(sink: CharSink, s: String, from: Int): Unit = {
    var i = from
    while (i < s.length) {
      sink.appendAscii(s.charAt(i))
      i += 1
    }
  }

  private def escape(sink: CharSink, s: String, safe: Array[Boolean]): Unit = {
    val len = s.length
    var i = 0
    while (i < len) {
      val c = s.charAt(i)
      // Above ASCII nothing needs escaping, so the sink takes the character as it is and encodes it
      // as UTF-8 (holding on to a high surrogate until its partner arrives).
      if (c >= 0x80) sink.append(c)
      else if (safe(c)) sink.appendAscii(c)
      else
        // There is deliberately no line-feed case. A value containing one is written in the `"""`
        // form, where the line feed is safe and passes through above, so no line feed ever reaches
        // this match. One that somehow did would fall to the escape below, which is also valid.
        c match {
          case '\\' => sink.append("\\\\")
          case '"' => sink.append("\\\"")
          case '\r' => sink.append("\\r")
          case '\t' => sink.append("\\t")
          case _ => appendUnicodeEscape(sink, c)
        }
      i += 1
    }
  }

  /** Write `c` as a `\\uXXXX` escape, the one form both string literals and IRIs accept. */
  private def appendUnicodeEscape(sink: CharSink, c: Char): Unit = {
    sink.append("\\u")
    var shift = 12
    while (shift >= 0) {
      sink.appendAscii(Hex((c >>> shift) & 0xf))
      shift -= 4
    }
  }
}

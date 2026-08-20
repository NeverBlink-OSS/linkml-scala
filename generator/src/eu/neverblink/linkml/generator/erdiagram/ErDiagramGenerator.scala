package eu.neverblink.linkml.generator.erdiagram

import eu.neverblink.linkml.generator.util.PruningMode.schemaRoot
import eu.neverblink.linkml.generator.util.{Printable, PruningMode, indent}
import eu.neverblink.linkml.schemaview.*

/** Generator for
  * [[https://mermaid.js.org/syntax/entityRelationshipDiagram.html Mermaid ER diagrams]].
  *
  * Classes become entities. Slots whose range is a type or an enum become attribute rows, carrying
  * the range name as the attribute type, `PK`/`UK` for `identifier`/`key` slots, and a trailing `?`
  * on the type for slots that are not `required`. Slots whose range is a class (inlined or
  * referenced) become relationship lines instead of rows.
  */
final class ErDiagramGenerator(using sv: SchemaView) {

  /** Generate the ER diagram model.
    *
    * @param options
    *   What to generate. See [[ErDiagramGenerator.Options]].
    */
  def generate(
      options: ErDiagramGenerator.Options = ErDiagramGenerator.Options(),
  ): ErDiagram = {
    import options.{optionalMarker, pruningMode}
    val query = pruningMode.derivedQuery(false, true)

    val classes = sv.classes.values
      .filter(cv => query.reachable(cv) && !cv.isAny) // Never plot linkml:Any
      .toSeq
      .sortBy(_.aliasedName)

    val entities =
      classes.map(cv => ErEntity(ErName.entity(cv.aliasedName), attributesOf(cv, optionalMarker)))

    // Only draw an edge if both ends are on the diagram. Mermaid would otherwise conjure the
    // missing end up as an empty box.
    val drawn = entities.map(_.name).toSet
    val relationships = classes.flatMap(relationshipsOf).filter(r => drawn.contains(r.to))

    ErDiagram(entities, relationships)
  }

  /** Generate the ER diagram and serialize it as Mermaid.
    *
    * @param options
    *   What to generate. See [[ErDiagramGenerator.Options]].
    */
  def serialize(
      options: ErDiagramGenerator.Options = ErDiagramGenerator.Options(),
  ): String =
    generate(options).print

  /** Slots of a class sorted by `rank`, then by name. */
  private def sortedAttributes(cv: ClassView): Seq[AttributeView] =
    cv.attributeViews.values.toSeq
      .sortBy(av => (av.slotView.slot.rank, av.slotView.slot.name))

  /** The attribute rows of an entity - every slot whose range is *not* a class. */
  private def attributesOf(cv: ClassView, optionalMarker: Boolean): Seq[ErAttribute] =
    sortedAttributes(cv).flatMap { av =>
      val slot = av.slotView.slot
      val dataType: Option[String] = av match {
        // A class-ranged slot is an edge, not a row.
        case _: ClassAttributeView => None
        case AnyView(_, _) => Some("Any")
        case TypeAttributeView(_, _, typeView) => Some(typeView.aliasedName)
        case EnumAttributeView(_, _, enumView) => Some(enumView.aliasedName)
      }
      dataType.map { base =>
        val keys =
          if slot.identifier then Seq(ErKey.PK)
          else if slot.key then Seq(ErKey.UK)
          else Nil
        ErAttribute(
          dataType = ErName.attributeToken(base),
          name = ErName.attributeToken(av.slotView.aliasedName),
          keys = keys,
          multivalued = slot.multivalued,
          optional = optionalMarker && !slot.required,
        )
      }
    }

  private def relationshipsOf(cv: ClassView): Seq[ErRelationship] =
    sortedAttributes(cv).collect {
      case av: ClassInlineAttributeView => relationship(cv, av, av.classView, identifying = true)
      case av: ClassReferenceAttributeView =>
        relationship(cv, av, av.classView, identifying = false)
    }

  private def relationship(
      cv: ClassView,
      av: AttributeView,
      range: ClassView,
      identifying: Boolean,
  ): ErRelationship = {
    val slot = av.slotView.slot
    ErRelationship(
      from = ErName.entity(cv.aliasedName),
      to = ErName.entity(range.aliasedName),
      // Nothing in LinkML states how many parents a child may have, so the owning end is left at
      // "exactly one".
      fromCardinality = ErCardinality.exactlyOne,
      toCardinality = (slot.required, slot.multivalued) match {
        case (true, true) => ErCardinality.oneOrMore
        case (false, true) => ErCardinality.zeroOrMore
        case (true, false) => ErCardinality.exactlyOne
        case (false, false) => ErCardinality.zeroOrOne
      },
      identifying = identifying,
      label = av.slotView.aliasedName,
    )
  }
}

/** A whole Mermaid ER diagram. */
final case class ErDiagram(entities: Seq[ErEntity], relationships: Seq[ErRelationship])
    extends Printable:
  override def print: String = {
    // Indent explicitly instead of through `indent`, so that the blank line separating the entities
    // from the relationships stays empty rather than becoming trailing whitespace.
    val body = Seq(entities, relationships)
      .filter(_.nonEmpty)
      .map(_.map(_.print.strip()).mkString("\n"))
      .mkString("\n\n")
      .linesIterator
      .map(line => if line.isEmpty then line else "  " + line)
      .mkString("\n")
    s"""%% GENERATED FROM LINKML
       |erDiagram
       |$body
       |""".stripMargin
  }

/** An entity, i.e. a LinkML class.
  *
  * @param name
  *   Entity name, already escaped by [[ErName.entity]]
  * @param attributes
  *   Attribute rows to list in the entity's block
  */
final case class ErEntity(name: String, attributes: Seq[ErAttribute]) extends Printable:
  override def print: String =
    // An attribute-less entity is written bare: `Foo { }` renders the same empty box with more ink.
    if attributes.isEmpty then name
    else indent"""$name {
              |  ${attributes.map(_.print).mkString("\n")}
              |}
              |""".stripMargin

/** An attribute row of an entity.
  *
  * @param dataType
  *   The range's name, already escaped by [[ErName.attributeToken]]
  * @param name
  *   The slot's name, already escaped by [[ErName.attributeToken]]
  * @param keys
  *   Key constraints to mark the attribute with
  * @param multivalued
  *   Whether to render the type as an array
  * @param optional
  *   Whether to mark the type optional with a trailing `?`
  */
final case class ErAttribute(
    dataType: String,
    name: String,
    keys: Seq[ErKey],
    multivalued: Boolean,
    optional: Boolean,
) extends Printable:
  override def print: String = {
    // Mermaid hangs both markers off the *type*: `string[]?`, never `string? []` or `string x?`.
    val typeExpr = dataType + (if multivalued then "[]" else "") + (if optional then "?" else "")
    val keyList = if keys.isEmpty then "" else keys.mkString(", ")
    s"$typeExpr $name $keyList".stripTrailing()
  }

/** A key constraint on an attribute.
  */
enum ErKey:
  case PK, FK, UK

/** How many entities may sit at one end of a relationship. The glyph is mirrored depending on which
  * end it is written at.
  */
enum ErCardinality(val fromGlyph: String, val toGlyph: String):
  case zeroOrOne extends ErCardinality("|o", "o|")
  case exactlyOne extends ErCardinality("||", "||")
  case zeroOrMore extends ErCardinality("}o", "o{")
  case oneOrMore extends ErCardinality("}|", "|{")

/** A relationship line between two entities.
  *
  * @param from
  *   Name of the entity that owns the slot, already escaped by [[ErName.entity]]
  * @param to
  *   Name of the entity in the slot's range, already escaped by [[ErName.entity]]
  * @param fromCardinality
  *   Cardinality at the owning end
  * @param toCardinality
  *   Cardinality at the range end
  * @param identifying
  *   Whether the range end is owned by the owning end (solid line) or independent (dashed line)
  * @param label
  *   The slot's name, escaped by [[ErName.label]] when printed
  */
case class ErRelationship(
    from: String,
    to: String,
    fromCardinality: ErCardinality,
    toCardinality: ErCardinality,
    identifying: Boolean,
    label: String,
) extends Printable:
  override def print: String = {
    val line = if identifying then "--" else ".."
    val spec = fromCardinality.fromGlyph + line + toCardinality.toGlyph
    s"$from $spec $to : ${ErName.label(label)}"
  }

/** Escaping of LinkML names into Mermaid ER tokens.
  *
  * The character classes below are transcribed from Mermaid's `erDiagram.jison` grammar. Mermaid
  * offers no escape mechanism anywhere - not in quoted entity names, labels or comments - so
  * characters it cannot represent are replaced rather than escaped.
  */
private[erdiagram] object ErName {

  /** Entity names may go unquoted if they match `UNICODE_TEXT`. */
  private val unquotedEntity = "^([^\\x00-\\x7F]|\\w|-|\\*|\\.)+$".r

  /** Mermaid's lexer is case-insensitive, so these cannot be unquoted entity names. `u` is included
    * because `u` directly before a connector lexes as `MD_PARENT`. `end` and `subgraph` are still
    * free in Mermaid 11, but are reserved by its unreleased subgraph support.
    */
  private val reservedEntities =
    Set("one", "many", "to", "class", "classdef", "style", "erdiagram", "u", "end", "subgraph")

  /** Any line containing `direction` followed by whitespace and a direction keyword is swallowed
    * whole by Mermaid's lexer and silently reinterpreted as a direction statement - the enclosing
    * quotes do not protect it. Joining the two words defuses that without dropping either.
    */
  private val directionStatement = "(?i)(direction)(\\s+)(TB|BT|RL|LR)".r

  /** Legal first characters of an `ATTRIBUTE_WORD`. */
  private val attributeHead = "[*A-Za-z_\\u00C0-\\uFFFF]".r

  /** Legal subsequent characters of an `ATTRIBUTE_WORD`. */
  private val attributeTail = "[A-Za-z0-9\\-_\\[\\]().,\\u00C0-\\uFFFF*]".r

  private def defuseDirection(s: String): String =
    directionStatement.replaceAllIn(s, m => s"${m.group(1)}_${m.group(3)}")

  /** Escape a name for use as an entity name, quoting it if it cannot stand bare. */
  def entity(raw: String): String = {
    val defused = defuseDirection(raw)
    val bare =
      unquotedEntity.matches(defused) &&
        !reservedEntities.contains(defused.toLowerCase) &&
        // Mermaid's lexer takes the first matching rule rather than the longest, and `NUM` comes
        // before `UNICODE_TEXT`. So a name like `1class` lexes as a number followed by a stray
        // keyword instead of as one name, and has to be quoted.
        !defused.head.isDigit
    if bare then defused else quote(defused)
  }

  /** Quote a string as an `ENTITY_NAME`, dropping the characters that cannot appear inside one. */
  private def quote(s: String): String = {
    val cleaned = s.map {
      // `"` ends the token, and `%`, `\` and the control characters are excluded from the rule.
      case '"' => '\''
      case '%' => '_'
      case '\\' => '/'
      case c if c.isControl => ' '
      case c => c
    }
    // The rule needs at least one character.
    if cleaned.isEmpty then "\"_\"" else s"\"$cleaned\""
  }

  /** Escape a name for use as an attribute type or attribute name. These cannot be quoted at all,
    * so anything outside the permitted character class is replaced with an underscore.
    */
  def attributeToken(raw: String): String = {
    // The tail class is a superset of the head class, so every character is held to the tail rule
    // and only the first position may then need help.
    val mapped = raw.map(c => if attributeTail.matches(c.toString) then c else '_')
    // A digit is legal in the tail but not the head, so the first character gets a prefix rather
    // than a replacement, which would throw it away.
    val headed =
      if mapped.isEmpty then "_"
      else if attributeHead.matches(mapped.head.toString) then mapped
      else "_" + mapped
    // Inside an entity block these three lex as `ATTRIBUTE_KEY`, never as a type or a name.
    if ErKey.values.exists(_.toString.equalsIgnoreCase(headed)) then headed + "_" else headed
  }

  /** Escape a string for use as a relationship label. Labels are always quoted: unquoted ones
    * cannot hold spaces, break on Mermaid's keywords, and let anything following them on the line
    * start a new statement.
    */
  def label(raw: String): String =
    // A `WORD` is `"[^"]*"`, so only the quote itself has to go.
    "\"" + defuseDirection(raw).replace('"', '\'').map(c => if c.isControl then ' ' else c) + "\""
}

object ErDiagramGenerator {

  /** Options for [[ErDiagramGenerator]].
    *
    * @param pruningMode
    *   How to prune the generated entities, schemaRoot by default (classes reachable from any
    *   element defined in the root schema).
    *
    * @param optionalMarker
    *   Whether to mark optional attributes with a trailing `?` on their type, which requires
    *   Mermaid 11.16 or newer.
    */
  final case class Options(
      pruningMode: PruningMode = schemaRoot,
      optionalMarker: Boolean = true,
  )
}

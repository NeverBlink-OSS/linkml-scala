package eu.neverblink.linkml.generator.erdiagram

import eu.neverblink.linkml.generator.CharDocumentGenerator
import eu.neverblink.linkml.generator.util.PruningMode.schemaRoot
import eu.neverblink.linkml.generator.util.{CharSink, PruningMode, StringSink}
import eu.neverblink.linkml.schemaview.*

/** Generator for
  * [[https://mermaid.js.org/syntax/entityRelationshipDiagram.html Mermaid ER diagrams]].
  *
  * Classes become entities. Slots whose range is a type or an enum become attribute rows, carrying
  * the range name as the attribute type, `PK`/`UK` for `identifier`/`key` slots, and a trailing `?`
  * on the type for slots that are not `required`. Slots whose range is a class (inlined or
  * referenced) become relationship lines instead of rows.
  */
final class ErDiagramGenerator(using sv: SchemaView)
    extends CharDocumentGenerator[ErDiagramGenerator.Options],
      ErDiagramRenamer {

  override protected def defaultOptions: ErDiagramGenerator.Options =
    ErDiagramGenerator.Options()

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

    val classes = sv.sortedClasses
      .filter(cv => query.reachable(cv) && !cv.isAny) // Never plot linkml:Any

    val entities = classes.map(cv => ErEntity(className(cv), attributesOf(cv, optionalMarker)))

    // Only draw an edge if both ends are on the diagram. Mermaid would otherwise conjure the
    // missing end up as an empty box.
    val drawn = entities.map(_.name).toSet
    val relationships = classes.flatMap(relationshipsOf).filter(r => drawn.contains(r.to))

    ErDiagram(entities, relationships)
  }

  /** Generate the ER diagram and write it as Mermaid.
    *
    * @param options
    *   What to generate. See [[ErDiagramGenerator.Options]].
    */
  override protected def writeChars(
      sink: CharSink,
      options: ErDiagramGenerator.Options,
  ): Unit =
    generate(options).writeTo(sink)

  /** The attribute rows of an entity - every slot whose range is *not* a class. */
  private def attributesOf(cv: ClassView, optionalMarker: Boolean): Seq[ErAttribute] =
    cv.sortedAttributeViews.flatMap { av =>
      val slot = av.slotView.slot
      val dataType: Option[String] = av match {
        // A class-ranged slot is an edge (relationship)
        case _: ClassAttributeView => None
        case AnyView(_, _) => Some("Any")
        case TypeAttributeView(_, _, typeView) => Some(typeName(typeView))
        case EnumAttributeView(_, _, enumView) => Some(enumName(enumView))
      }
      dataType.map { typeToken =>
        val keys =
          if slot.identifier then Seq(ErKey.PK)
          else if slot.key then Seq(ErKey.UK)
          else Nil
        ErAttribute(
          dataType = typeToken,
          name = classAttributeName(cv, slot),
          keys = keys,
          multivalued = slot.multivalued,
          optional = optionalMarker && !slot.required,
        )
      }
    }

  private def relationshipsOf(cv: ClassView): Seq[ErRelationship] =
    cv.sortedAttributeViews.collect {
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
      from = className(cv),
      to = className(range),
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
      label = classAttributeName(cv, slot),
    )
  }
}

/** A whole Mermaid ER diagram. */
final case class ErDiagram(entities: Seq[ErEntity], relationships: Seq[ErRelationship]):

  /** Write the diagram as Mermaid. */
  def writeTo(sink: CharSink): Unit = {
    sink.append("%% GENERATED FROM LINKML\nerDiagram\n")
    val body = new ErBody(sink)
    entities.foreach(_.writeTo(body))
    // A blank line separates the two groups, but only when there are two groups to separate.
    if entities.nonEmpty && relationships.nonEmpty then body.startBlankLine()
    relationships.foreach(_.writeTo(body))
    sink.append('\n')
  }

  /** The whole diagram as one string. Prefer [[writeTo]] where a sink is available. */
  def print: String = {
    val sink = new StringSink
    writeTo(sink)
    sink.result
  }

/** Writes the indented body of a diagram, line by line.
  *
  * Newlines go *between* lines, so nothing follows the last one and [[ErDiagram]] stays in charge
  * of how the document ends. Empty lines are written without indentation, which is what keeps the
  * blank line between the entities and the relationships genuinely empty instead of trailing
  * whitespace.
  */
private final class ErBody(sink: CharSink) {
  private var started = false

  /** Start a line, indented one level for the `erDiagram` block plus [[extra]] spaces of nesting.
    */
  def startLine(extra: Int = 0): Unit = {
    newline()
    var spaces = 2 + extra
    while spaces > 0 do {
      sink.append(' ')
      spaces -= 1
    }
  }

  /** Start an empty line. */
  def startBlankLine(): Unit = newline()

  def append(s: String): Unit = sink.append(s)

  def append(c: Char): Unit = sink.append(c)

  private def newline(): Unit = {
    if started then sink.append('\n')
    started = true
  }
}

/** An entity, i.e. a LinkML class.
  *
  * @param name
  *   Entity name, already escaped by the renamer
  * @param attributes
  *   Attribute rows to list in the entity's block
  */
final case class ErEntity(name: String, attributes: Seq[ErAttribute]):
  private[erdiagram] def writeTo(body: ErBody): Unit = {
    body.startLine()
    body.append(name)
    // An attribute-less entity is written bare: `Foo { }` renders the same empty box with more ink.
    if attributes.nonEmpty then {
      body.append(" {")
      attributes.foreach { attribute =>
        body.startLine(2)
        attribute.writeTo(body)
      }
      body.startLine()
      body.append('}')
    }
  }

/** An attribute row of an entity.
  *
  * @param dataType
  *   The range's name, already escaped by the renamer
  * @param name
  *   The slot's name, already escaped by the renamer
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
):
  /** Written into a line that [[ErEntity]] has already started, since an attribute row only exists
    * inside an entity's block.
    */
  private[erdiagram] def writeTo(body: ErBody): Unit = {
    // Mermaid hangs both markers off the *type*: `string[]?`, never `string? []` or `string x?`.
    body.append(dataType)
    if multivalued then body.append("[]")
    if optional then body.append('?')
    body.append(' ')
    body.append(name)
    // The key list is last, so with no keys the row simply ends after the name.
    if keys.nonEmpty then {
      body.append(' ')
      var first = true
      keys.foreach { key =>
        if !first then body.append(", ")
        body.append(key.toString)
        first = false
      }
    }
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
  *   Name of the entity that owns the slot, already escaped by the renamer
  * @param to
  *   Name of the entity in the slot's range, already escaped by the renamer
  * @param fromCardinality
  *   Cardinality at the owning end
  * @param toCardinality
  *   Cardinality at the range end
  * @param identifying
  *   Whether the range end is owned by the owning end (solid line) or independent (dashed line)
  * @param label
  *   The slot's name, escaped by quoting when written
  */
case class ErRelationship(
    from: String,
    to: String,
    fromCardinality: ErCardinality,
    toCardinality: ErCardinality,
    identifying: Boolean,
    label: String,
):
  private[erdiagram] def writeTo(body: ErBody): Unit = {
    body.startLine()
    body.append(from)
    body.append(' ')
    body.append(fromCardinality.fromGlyph)
    body.append(if identifying then "--" else "..")
    body.append(toCardinality.toGlyph)
    body.append(' ')
    body.append(to)
    body.append(" : \"")
    body.append(label)
    body.append("\"")
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

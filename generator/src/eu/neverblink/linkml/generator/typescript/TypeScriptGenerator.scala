package eu.neverblink.linkml.generator.typescript

import eu.neverblink.linkml.generator.CharDocumentGenerator
import eu.neverblink.linkml.generator.util.{GeneratedHeader, PruningMode}
import eu.neverblink.linkml.metamodel.CommonMetadata
import eu.neverblink.linkml.rdf.io.CharSink
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.StringUtils.{hexDigit, splitLines}
import eu.neverblink.linkml.schemaview.*

import scala.collection.mutable

/** Generates TypeScript types that describe the JSON form of the data 1:1, the same as
  * [[eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator]]. The output has no runtime
  * code and no imports: data is loaded with `JSON.parse(text) as X` and dumped with
  * `JSON.stringify(x)`.
  *
  * Classes become flat interfaces (all inherited slots listed, no `extends`). Classes with a type
  * designator (`designates_type`) become discriminated unions of their concrete descendants.
  */
final class TypeScriptGenerator(using sv: SchemaView)
    extends CharDocumentGenerator[TypeScriptGenerator.Options],
      TypeScriptRenamer {
  import TypeScriptGenerator.*

  override protected def defaultOptions: Options = Options()

  /** Name of the union type standing for a class and its subclasses, if the class needs one. */
  def unionName(cls: ClassView): Option[String] =
    if cls.isTypeDesignatorUnion then {
      val name = className(cls)
      Some(if cls.isConcrete then "Any".concat(name) else name)
    } else None

  /** TS type to use where the class is the range of a slot: the union if there is one, otherwise
    * the interface.
    */
  private def classRef(cls: ClassView): String = unionName(cls).getOrElseFast(className(cls))

  /** Does the class have an interface of its own? Abstract designated classes are only a union. */
  private def hasInterface(cls: ClassView): Boolean =
    cls.isConcrete || !cls.isTypeDesignatorUnion

  /** What to generate: whatever the pruning mode keeps, plus the members of any designated union it
    * keeps, and whatever those reach in turn. A slot ranging over a designated class uses the
    * union, so its members must be there too.
    */
  private def reachability(mode: PruningMode): SchemaReachabilityQuery =
    sv.withTypeDesignatorMembers(mode.derivedQuery(true, false), true, false)

  private def hasRequiredContent(cls: ClassView, key: String): Boolean =
    cls.derivedAttributes.exists { case (name, slot) => name != key && slot.slot.required }

  /** TS type of a class inlined in the given form. Records the use of the `KeyOptional` helper. */
  private def inlinedType(cls: ClassView, inlineType: InlineType, ctx: Context): String = {
    val ref = classRef(cls)
    inlineType match {
      case InlineType.plain | InlineType.optional => ref
      case InlineType.list => arrayOf(ref)
      case InlineType.dict(CollectionForm.CompactDict(key)) =>
        ctx.usesKeyOptional = true
        val entry = keyOptional(ref, cls, key)
        val nullable =
          if ctx.options.includeNull && !hasRequiredContent(cls, key) then entry.concat(" | null")
          else entry
        s"Record<string, $nullable>"
      case InlineType.dict(CollectionForm.SimpleDict(key, value)) =>
        ctx.usesKeyOptional = true
        val valueType = propertyType(cls.attributeViews(value), cls, ctx)
        s"Record<string, $valueType | ${keyOptional(ref, cls, key)}>"
    }
  }

  private def keyOptional(ref: String, cls: ClassView, key: String): String =
    s"KeyOptional<$ref, ${stringLiteral(slotName(cls.derivedAttributes(key)))}>"

  /** TS type of an attribute, without the `| null` added for optional slots. */
  private def attributeType(attribute: AttributeView, owner: ClassView, ctx: Context): String = {
    val multivalued = attribute.slotView.slot.multivalued
    attribute match {
      case _: AnyView => arrayOfIf(multivalued, "unknown")
      case ClassInlineAttributeView(_, _, classView, inlineType) =>
        inlinedType(classView, inlineType, ctx)
      case ClassReferenceAttributeView(_, _, _, identifierView) =>
        arrayOfIf(multivalued, runtimeType(identifierView.typeView))
      case tav: TypeAttributeView =>
        val literals =
          if tav.slotView.slot.designatesType && owner.isConcrete then owner.typeDesignatorValues
          else Nil
        val base =
          if literals.isEmpty then runtimeType(tav.typeView)
          else literals.map(stringLiteral).mkString(" | ")
        arrayOfIf(multivalued, base)
      case EnumAttributeView(_, _, enumView) => arrayOfIf(multivalued, enumName(enumView))
    }
  }

  /** TS type of an attribute, as it appears in a property. */
  private def propertyType(attribute: AttributeView, owner: ClassView, ctx: Context): String = {
    val tpe = attributeType(attribute, owner, ctx)
    if ctx.options.includeNull && !attribute.slotView.slot.required then tpe.concat(" | null")
    else tpe
  }

  private def docLines(element: CommonMetadata, options: Options): Seq[String] = {
    val title = element.title.flatMapFast(_.inLanguage(options.metadataLanguage))
    val description = element.description.flatMapFast(_.inLanguage(options.metadataLanguage))
    val text = title match {
      case Some(t) =>
        description match {
          case Some(d) => Some(s"$t: $d")
          case _ => title
        }
      case _ => description
    }
    text.foldFast(Nil: Seq[String])(t => splitLines(t.strip))
  }

  private def writeDoc(sink: CharSink, lines: Seq[String], indent: String): Unit =
    if lines.nonEmpty then {
      val escaped = lines.map(_.replace("*/", "*\\/"))
      sink.append(indent)
      if escaped.sizeIs == 1 then {
        sink.append("/** ")
        sink.append(escaped.head)
        sink.append(" */\n")
      } else {
        sink.append("/**\n")
        escaped.foreach { line =>
          sink.append(indent)
          sink.append(if line.isEmpty then " *" else " * ".concat(line))
          sink.append('\n')
        }
        sink.append(indent)
        sink.append(" */\n")
      }
    }

  private def writeInterface(sink: CharSink, cls: ClassView, ctx: Context): Unit = {
    writeDoc(sink, docLines(cls.cls, ctx.options), "")
    sink.append("export interface ")
    sink.append(className(cls))
    sink.append(" {\n")
    cls.sortedAttributeViews.foreach { attribute =>
      val slot = attribute.slotView
      val doc = docLines(slot.slot, ctx.options) ++ (attribute match {
        case ref: ClassReferenceAttributeView => Seq(s"Reference to ${className(ref.classView)}")
        case _ => Nil
      })
      writeDoc(sink, doc, "  ")
      sink.append("  ")
      sink.append(propertyKey(slotName(slot)))
      if !slot.slot.required then sink.append('?')
      sink.append(": ")
      sink.append(propertyType(attribute, cls, ctx))
      sink.append(";\n")
    }
    if ctx.options.open || cls.allowsExtraSlots then sink.append("  [key: string]: unknown;\n")
    sink.append("}\n")
  }

  private def writeUnion(sink: CharSink, cls: ClassView, name: String, options: Options): Unit = {
    if !cls.isConcrete then writeDoc(sink, docLines(cls.cls, options), "")
    sink.append("export type ")
    sink.append(name)
    sink.append(" =")
    cls.typeDesignatorMembers.foreach { member =>
      sink.append("\n  | ")
      sink.append(className(member))
    }
    sink.append(";\n")
  }

  private def writeEnum(sink: CharSink, ev: EnumView, options: Options): Unit = {
    writeDoc(sink, docLines(ev._enum, options), "")
    sink.append("export type ")
    sink.append(enumName(ev))
    sink.append(" =")
    val values = ev._enum.permissibleValues.values
    if values.isEmpty then sink.append(" string")
    else
      values.foreach { pv =>
        sink.append("\n  | ")
        sink.append(stringLiteral(permissibleValueName(ev, pv)))
      }
    sink.append(";\n")
  }

  /** Check that no two generated types share a name. */
  private def checkNames(classes: Seq[ClassView], enums: Seq[EnumView]): Unit = {
    val seen = mutable.HashMap.empty[String, String]
    def claim(name: String, owner: String): Unit =
      seen.put(name, owner).foreachFast { other =>
        throw new IllegalArgumentException(
          s"$owner and $other both map to the TypeScript name '$name'. " +
            "Rename one of them to generate TypeScript.",
        )
      }
    claim("KeyOptional", "the KeyOptional helper type")
    classes.foreach { cls =>
      if hasInterface(cls) then claim(className(cls), s"class '${cls.name}'")
      if cls.isConcrete then
        unionName(cls).foreachFast { name =>
          claim(name, s"the union of class '${cls.name}' and its subclasses")
        }
      if !hasInterface(cls) then claim(className(cls), s"the union of abstract class '${cls.name}'")
    }
    enums.foreach(ev => claim(enumName(ev), s"enum '${ev.name}'"))
  }

  override protected def writeChars(sink: CharSink, options: Options): Unit = {
    val ctx = Context(options)
    val query = reachability(options.pruningMode)
    val classes = sv.sortedClasses.filter(c => !c.isAny && query.reachable(c))
    val enums =
      sv.enums.values.filter(query.reachable).toVector.sortBy(ev => sv.elementOrder(ev._enum))
    checkNames(classes, enums)

    sink.append(GeneratedHeader("//"))
    classes.foreach { cls =>
      sink.append('\n')
      if hasInterface(cls) then writeInterface(sink, cls, ctx)
      unionName(cls).foreachFast { name =>
        if hasInterface(cls) then sink.append('\n')
        writeUnion(sink, cls, name, options)
      }
    }
    enums.foreach { ev =>
      sink.append('\n')
      writeEnum(sink, ev, options)
    }
    if ctx.usesKeyOptional then {
      sink.append('\n')
      sink.append(keyOptionalDefinition)
    }
  }
}

object TypeScriptGenerator {

  /** Options for [[TypeScriptGenerator]].
    *
    * @param pruningMode
    *   Which classes and enums to generate. `skip` (the default) generates all of them. The members
    *   of a designated union are always generated along with the union.
    * @param includeNull
    *   Allows null values for optional slots and compact dictionary entries without required
    *   content beyond the key, as in the JSON Schema generator. Default: false
    * @param open
    *   Whether interfaces should allow additional properties (`[key: string]: unknown`), as
    *   `additionalProperties` in the JSON Schema generator. Default: false. Interfaces of classes
    *   whose `extra_slots` allows extra data allow them anyway.
    * @param metadataLanguage
    *   Which language to use for titles and descriptions in doc comments.
    */
  final case class Options(
      pruningMode: PruningMode = PruningMode.skip,
      includeNull: Boolean = false,
      open: Boolean = false,
      metadataLanguage: String = "en",
  )

  /** Mutable state of one generator run. */
  private final case class Context(options: Options) {
    var usesKeyOptional: Boolean = false
  }

  /** Helper type for dict entries, where the key slot moves out of the object. Distributes over
    * unions, so that designated classes keep working. The result is flattened into a single object
    * type, because TS does not reject e.g. a number for the intersection `{} & { id?: string }`.
    */
  val keyOptionalDefinition: String =
    """/** The object `T`, with the key slot `K` optional, as used in dict values. */
      |export type KeyOptional<T, K extends keyof T> = T extends unknown
      |  ? Omit<T, K> & Partial<Pick<T, K>> extends infer O ? { [P in keyof O]: O[P] } : never
      |  : never;
      |""".stripMargin

  /** TS type of a LinkML type. Its core type is enough: all the string-based types (dates, URIs...)
    * are plain strings in JSON. A `union_of` type is the union of its members' types.
    */
  def runtimeType(tv: TypeView): String =
    if tv.isUnion then tv.unionAlternatives.map(runtimeType).distinct.mkString(" | ")
    else
      tv.coreType match {
        case _: StringType.type => "string"
        case _: BooleanType.type => "boolean"
        case _: AnyType.type => "unknown"
        case _ => "number" // integer, float, double and decimal: the rest of the core types
      }

  private def isPlainKey(key: String): Boolean = {
    val len = key.length
    len > 0 && {
      val first = key.charAt(0)
      (Case.isAlphaUpper(first) || Case.isAlphaLower(first) || first == '_' || first == '$') && {
        // The first char passed the stricter check above
        var i = 1
        while (i < len && { val c = key.charAt(i); Case.isStandard(c) || c == '$' }) i += 1
        i == len
      }
    }
  }

  /** A property key, quoted if it is not a plain identifier. */
  def propertyKey(key: String): String = if isPlainKey(key) then key else stringLiteral(key)

  /** A double-quoted TypeScript string literal. */
  def stringLiteral(value: String): String = {
    val sb = new java.lang.StringBuilder(value.length + 2)
    sb.append('"')
    val len = value.length
    var i = 0
    while (i < len) {
      value.charAt(i) match {
        case '"' => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c if c < ' ' || c == '\u2028' || c == '\u2029' =>
          sb.append('\\').append('u').append(hexDigit(c >> 12)).append(hexDigit(c >> 8))
            .append(hexDigit(c >> 4)).append(hexDigit(c))
        case c => sb.append(c)
      }
      i += 1
    }
    sb.append('"').toString
  }

  private def arrayOf(tpe: String): String =
    if tpe.indexOf('|') >= 0 || tpe.indexOf(' ') >= 0 then s"($tpe)[]" else tpe.concat("[]")

  private def arrayOfIf(condition: Boolean, tpe: String): String =
    if condition then arrayOf(tpe) else tpe
}

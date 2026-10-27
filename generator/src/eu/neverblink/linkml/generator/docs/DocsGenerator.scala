package eu.neverblink.linkml.generator.docs

import eu.neverblink.linkml.generator.CharDocumentGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlRenamer
import eu.neverblink.linkml.generator.pydantic.{PydanticGenerator, PydanticRenamer}
import eu.neverblink.linkml.generator.scala.{ScalaGenerator, ScalaRenamer}
import eu.neverblink.linkml.generator.typescript.TypeScriptRenamer
import eu.neverblink.linkml.generator.util.{GeneratedHeader, PruningMode, Renamer}
import eu.neverblink.linkml.metamodel.{
  AnonymousEnumExpression,
  AnonymousSlotExpression,
  CommonMetadata,
  Element,
  ReachabilityQuery,
  SlotDefinition,
}
import eu.neverblink.linkml.rdf.io.{CharSink, StringSink}
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.LocalizedText.*
import eu.neverblink.linkml.runtime.{
  HasMetadataLanguage,
  MetadataLanguage,
  PrefixMap,
  Uri,
  UriOrCurie,
}
import eu.neverblink.linkml.schemaview.*

import scala.collection.mutable

/** Generator for Markdown documentation of a LinkML schema.
  *
  * The documentation has an index page plus one page for each class, slot, enum and type. Pages
  * link to each other with relative links, so they work on GitHub and in static site tools like
  * Docusaurus, MkDocs or VitePress without any changes.
  *
  * This generator has two entrypoints:
  *   - [[generateFiles]] writes `index.md` plus `classes/<name>.md`, `slots/<name>.md`,
  *     `enums/<name>.md` and `types/<name>.md`.
  *   - [[writeTo]] and [[serialize]] write the same pages as sections of one document, which link
  *     to each other with anchors.
  *
  * Descriptions are Markdown and are copied to the pages as they are.
  *
  * To add sections to the pages, such as diagrams, extend this class and override [[indexSections]]
  * or [[elementSections]].
  */
class DocsGenerator(using sv: SchemaView) extends CharDocumentGenerator[DocsGenerator.Options] {
  import DocsGenerator.*
  import DocsSite.Page

  override protected def defaultOptions: Options = Options()

  /** Write sections at the end of the index page. Does nothing here, and can be overridden to add
    * sections such as a diagram of the schema.
    *
    * @param md
    *   Where to write. Its methods escape text from the schema, and its headings get the right
    *   level whether the page is a file of its own or a section of one document.
    * @param site
    *   The documented elements and their pages
    */
  protected def indexSections(md: Markdown, site: DocsSite): Unit = ()

  /** Write sections at the end of the page about [[el]], a class, slot, enum or type. Does nothing
    * here, and can be overridden to add sections such as a diagram of a class.
    *
    * @param md
    *   Where to write. See [[indexSections]].
    * @param site
    *   The documented elements and their pages
    */
  protected def elementSections(md: Markdown, el: ElementView[?, ?], site: DocsSite): Unit = ()

  /** The documentation as `index.md` plus one file for each documented element, as pairs of file
    * path and content. Paths are relative to the output folder and use `/`. With
    * [[DocsGenerator.Options.singleFile]], it is only `docs.md`, with what [[serialize]] gives.
    *
    * @param options
    *   What to document. See [[DocsGenerator.Options]].
    */
  def generateFiles(options: Options = Options()): Seq[(path: String, content: String)] =
    if (options.singleFile) Seq((path = "docs.md", content = serialize(options)))
    else pageFiles(options)

  private def pageFiles(options: Options): Seq[(path: String, content: String)] = {
    val site = new DocsSite(options.pruningMode, singleFile = false)
    val writer = new PageWriter(site, options)
    val files = Seq.newBuilder[(path: String, content: String)]
    files.addOne((path = "index.md", content = render(md => writer.index(md))))
    def addPages(elements: Seq[ElementView[?, ?]]): Unit =
      elements.foreach { el =>
        val page = site.pageOf(el).get
        files.addOne((path = page.path, content = render(md => writer.element(md, el, page))))
      }
    addPages(site.classes)
    addPages(site.slots)
    addPages(site.enums)
    addPages(site.types)
    files.result()
  }

  /** The documentation as one Markdown document: the index, then a section for each documented
    * element.
    *
    * @param options
    *   What to document. See [[DocsGenerator.Options]].
    */
  override protected def writeChars(sink: CharSink, options: Options): Unit = {
    val site = new DocsSite(options.pruningMode, singleFile = true)
    val writer = new PageWriter(site, options)
    sink.append(GeneratedHeader.html)
    writer.index(new Markdown(sink, 0))
    val md = new Markdown(sink, 1)
    def addSections(elements: Seq[ElementView[?, ?]]): Unit =
      elements.foreach { el =>
        val page = site.pageOf(el).get
        md.startAnchor()
        page.appendAnchor(md)
        md.endAnchor()
        writer.element(md, el, page)
      }
    addSections(site.classes)
    addSections(site.slots)
    addSections(site.enums)
    addSections(site.types)
  }

  /** One page as a string, starting with the generated header. */
  private def render(write: Markdown => Unit): String = {
    val sink = new StringSink
    sink.append(GeneratedHeader.html)
    write(new Markdown(sink, 0))
    sink.result
  }

  /** Writes the pages of one [[DocsSite]]. */
  private final class PageWriter(site: DocsSite, options: Options) {
    private given MetadataLanguage = MetadataLanguage(options.metadataLanguage)

    private val treeRoot: Option[ClassView] = sv.treeRoot

    /** The index page: what the schema is, and a table of each kind of element. */
    def index(md: Markdown): Unit = {
      val schema = sv.root
      md.startHeading(1)
      md.text(schema.title.localized.getOrElseFast(schema.name))
      md.endBlock()
      deprecation(md, schema)
      schema.description.localized.foreachFast(md.paragraph)
      md.startItem("ID")
      uri(md, schema.id, sv.rootPrefixes)
      md.endItem()
      if (schema.title.isDefined) {
        md.startItem("Name")
        md.code(schema.name)
        md.endItem()
      }
      schema.version.foreachFast { v =>
        md.startItem("Version")
        md.text(v)
        md.endItem()
      }
      schema.license.foreachFast { l =>
        md.startItem("License")
        // Often the address of the license, which is then linked
        if (isLinkable(l)) {
          md.append('[')
          md.text(l)
          md.append("](")
          md.append(l)
          md.append(')')
        } else md.text(l)
        md.endItem()
      }
      treeRoot.foreachFast { cv =>
        md.startItem("Tree root")
        link(md, cv, None)
        md.endItem()
      }
      md.endList()

      elementTable(md, "Classes", "Class", site.classes) { cv =>
        if (cv.cls.`abstract`) " (abstract)" else if (cv.cls.mixin) " (mixin)" else ""
      }
      elementTable(md, "Slots", "Slot", site.slots)(_ => "")
      elementTable(md, "Enums", "Enum", site.enums)(_ => "")
      elementTable(md, "Types", "Type", site.types)(_ => "")
      indexSections(md, site)
    }

    /** A table of [[elements]] with their descriptions, under its own heading. */
    private def elementTable[V <: ElementView[?, ?]](
        md: Markdown,
        title: String,
        column: String,
        elements: Seq[V],
    )(suffix: V => String): Unit =
      if (elements.nonEmpty) {
        md.heading(2, title)
        md.tableHeader(column, "Description")
        elements.foreach { el =>
          md.startRow()
          link(md, el, None)
          md.append(suffix(el))
          md.nextCell()
          summary(md, meta(el))
          md.endRow()
        }
        md.endList()
      }

    /** The page about [[el]], whose own page is [[page]]. */
    def element(md: Markdown, el: ElementView[?, ?], page: Page): Unit = {
      val from = Some(page)
      md.startHeading(1)
      md.append(page.kind.label)
      md.append(": ")
      md.text(el.name)
      md.endBlock()
      deprecation(md, meta(el))
      meta(el).description.localized.foreachFast(md.paragraph)
      nameItems(md, el)
      el match {
        case cv: ClassView => classPage(md, cv, from)
        case s: SlotView => slotPage(md, s, from)
        case ev: EnumView => enumPage(md, ev, from)
        case tv: TypeView => typePage(md, tv, from)
        case _ =>
      }
      elementSections(md, el, site)
    }

    private def classPage(md: Markdown, cv: ClassView, from: Option[Page]): Unit = {
      val cls = cv.cls
      uriItem(md, cv)
      if (cls.`abstract`)
        md.item("Abstract", "yes, so data can only have instances of its subclasses")
      if (cls.mixin) md.item("Mixin", "yes, so other classes can add its slots with `mixins`")
      if (treeRoot.exists(_ eq cv)) md.item("Tree root", "yes")
      // Rules are conditions on several slots at once, which need more than a line each
      if (cls.rules.nonEmpty) {
        md.startItem("Rules")
        md.number(cls.rules.size)
        md.append(", not shown here. See the schema.")
        md.endItem()
      }
      cls.isA.foreachFast { parent =>
        md.startItem("Parent")
        classLinkByName(md, parent.value, from)
        md.endItem()
      }
      if (cls.mixins.nonEmpty) {
        md.startItem("Mixins")
        joined(md, cls.mixins)(m => classLinkByName(md, m.value, from))
        md.endItem()
      }
      val children = site.childrenOf(cv)
      if (children.nonEmpty) {
        md.startItem("Children")
        joined(md, children)(c => link(md, c, from))
        md.endItem()
      }
      val mixinUsers = site.mixinUsersOf(cv)
      if (mixinUsers.nonEmpty) {
        md.startItem("Mixed into")
        joined(md, mixinUsers)(c => link(md, c, from))
        md.endItem()
      }
      metadataItems(md, cv)
      md.endList()

      inheritance(md, cv, children, from)

      val attributes = cv.sortedAttributeViews
      if (attributes.nonEmpty) {
        md.heading(2, "Slots")
        md.tableHeader("Name", "Range", "Cardinality", "Description", "Inherited from")
        attributes.foreach { av =>
          val slot = av.slotView.slot
          md.startRow()
          slotName(md, cv, av, from)
          md.append(slotMarker(slot))
          md.nextCell()
          range(md, av, from)
          md.nextCell()
          cardinality(md, slot)
          md.nextCell()
          summary(md, slot)
          md.nextCell()
          site.inheritedFrom(cv, slot.name).foreachFast(anc => link(md, anc, from))
          md.endRow()
        }
        md.endList()
      }

      uniqueKeys(md, cv, from)
      usages(md, cv, from)
    }

    /** A table of the `unique_keys` of [[cv]], with the slots of each. */
    private def uniqueKeys(md: Markdown, cv: ClassView, from: Option[Page]): Unit =
      if (cv.cls.uniqueKeys.nonEmpty) {
        md.heading(2, "Unique keys")
        md.tableHeader("Name", "Slots", "Description")
        cv.cls.uniqueKeys.foreach { (name, key) =>
          md.startRow()
          md.text(name)
          md.nextCell()
          joined(md, key.uniqueKeySlots) { slot =>
            val page = if (site.isTopLevelSlot(cv, slot.value)) site.slotPage(slot.value) else None
            page.foldFast(md.text(slot.value))(p => pageLink(md, slot.value, from, p))
          }
          md.nextCell()
          val description = key.description.localized
          description.foreachFast(md.oneLine)
          if (key.considerNullsInequal) {
            if (description.isDefined) md.append(' ')
            md.append("Missing values never match.")
          }
          md.endRow()
        }
        md.endList()
      }

    /** The `is_a` ancestors of [[cv]] and its direct children, as a nested list. Left out if [[cv]]
      * has neither.
      */
    private def inheritance(
        md: Markdown,
        cv: ClassView,
        children: Seq[ClassView],
        from: Option[Page],
    ): Unit = {
      val ancestors = mutable.ListBuffer.empty[String]
      val seen = mutable.HashSet(cv.name)
      var parent = cv.cls.isA.mapFast(_.value)
      while (parent.isDefined && seen.add(parent.get)) {
        ancestors.prepend(parent.get)
        parent = sv.classes.get(parent.get).flatMapFast(_.cls.isA.mapFast(_.value))
      }
      if (ancestors.nonEmpty || children.nonEmpty) {
        md.heading(2, "Inheritance")
        var depth = 0
        ancestors.foreach { name =>
          md.startNestedItem(depth)
          classLinkByName(md, name, from)
          md.endItem()
          depth += 1
        }
        md.startNestedItem(depth)
        md.append("**")
        md.text(cv.name)
        md.append("**")
        md.endItem()
        children.foreach { child =>
          md.startNestedItem(depth + 1)
          link(md, child, from)
          md.endItem()
        }
        md.endList()
      }
    }

    private def slotPage(md: Markdown, s: SlotView, from: Option[Page]): Unit = {
      val slot = s.slot
      uriItem(md, s)
      md.startItem("Range")
      slotRange(md, s, from)
      md.endItem()
      md.startItem("Cardinality")
      cardinality(md, slot)
      md.endItem()
      slotItems(md, slot)
      slot.isA.foreachFast { parent =>
        md.startItem("Parent")
        slotLinkByName(md, parent.value, from)
        md.endItem()
      }
      if (slot.mixins.nonEmpty) {
        md.startItem("Mixins")
        joined(md, slot.mixins)(m => slotLinkByName(md, m.value, from))
        md.endItem()
      }
      metadataItems(md, s)
      md.endList()

      val usages = site.usagesOf(s)
      if (usages.nonEmpty) {
        md.heading(2, "Classes")
        md.paragraph("Classes that have this slot, with the range and cardinality it has in each.")
        md.tableHeader("Class", "Range", "Cardinality")
        usages.foreach { usage =>
          md.startRow()
          link(md, usage.classView, from)
          md.nextCell()
          range(md, usage.attribute, from)
          md.nextCell()
          cardinality(md, usage.attribute.slotView.slot)
          md.endRow()
        }
        md.endList()
      }
      examples(md, slot)
    }

    private def enumPage(md: Markdown, ev: EnumView, from: Option[Page]): Unit = {
      uriItem(md, ev)
      metadataItems(md, ev)
      md.endList()

      val hasSources = valueSources(md, ev, from)
      md.heading(2, "Permissible values")
      // Values with a rank come first. The others keep the order they were written in.
      val values =
        ev._enum.permissibleValues.values.toVector.sortBy(_.rank.getOrElseFast(Int.MaxValue))
      if (values.isEmpty)
        md.paragraph(
          if (hasSources) "The values come from the sources above, so they aren't listed here."
          else "This enum has no fixed list of values.",
        )
      else {
        md.tableHeader("Value", "Meaning", "Description")
        values.foreach { pv =>
          md.startRow()
          md.code(pv.text)
          pv.alias.foreachFast { a =>
            md.append(" (")
            md.code(a)
            md.append(" in data)")
          }
          md.nextCell()
          pv.meaning.foreachFast(m => uri(md, m, ev.definingPrefixes))
          md.nextCell()
          pv.title.localized.foreachFast { t =>
            md.append("**")
            md.text(t)
            md.append("**")
            if (pv.description.isDefined) md.append(": ")
          }
          pv.description.localized.foreachFast(md.allOnOneLine)
          md.endRow()
        }
        md.endList()
      }
      usages(md, ev, from)
    }

    /** A "Values from" section with where the values of [[ev]] come from, other than its own
      * permissible values: the enums it inherits from, the value sets it includes or excludes, its
      * concepts, its code set, and the query that finds its values in an ontology. Left out if it
      * has none of them.
      *
      * @return
      *   Whether the section was written
      */
    private def valueSources(md: Markdown, ev: EnumView, from: Option[Page]): Boolean = {
      val e = ev._enum
      val hasSources = e.inherits.nonEmpty || e.include.nonEmpty || e.minus.nonEmpty ||
        e.concepts.nonEmpty || e.codeSet.isDefined || e.reachableFrom.isDefined
      if (hasSources) {
        given PrefixMap = ev.definingPrefixes
        md.heading(2, "Values from")
        if (e.inherits.nonEmpty) {
          md.startItem("Inherits")
          joined(md, e.inherits)(i => enumLinkByName(md, i.value, from))
          md.endItem()
        }
        e.include.foreach { x =>
          md.startItem("Includes")
          enumExpression(md, x, from)
          md.endItem()
        }
        e.minus.foreach { x =>
          md.startItem("Excludes")
          enumExpression(md, x, from)
          md.endItem()
        }
        urisItem(md, "Concepts", e.concepts)
        e.codeSet.foreachFast { c =>
          md.startItem("Code set")
          uri(md, c, ev.definingPrefixes)
          md.endItem()
        }
        e.reachableFrom.foreachFast(reachability(md, _))
        md.endList()
      }
      hasSources
    }

    /** A short summary of a value set in the `include` or `minus` of an enum, such as "values `a`,
      * `b`", with its parts separated by "; ".
      */
    private def enumExpression(md: Markdown, x: AnonymousEnumExpression, from: Option[Page])(using
        prefixes: PrefixMap,
    ): Unit = {
      var first = true
      def part(label: String)(write: => Unit): Unit = {
        if (!first) md.append("; ")
        md.append(label)
        write
        first = false
      }
      if (x.permissibleValues.nonEmpty)
        part("values ")(joined(md, x.permissibleValues.keys)(md.code))
      if (x.inherits.nonEmpty)
        part("values of ")(joined(md, x.inherits)(i => enumLinkByName(md, i.value, from)))
      if (x.concepts.nonEmpty)
        part("concepts ")(joined(md, x.concepts)(c => uri(md, c, prefixes)))
      x.codeSet.foreachFast(c => part("code set ")(uri(md, c, prefixes)))
      x.reachableFrom.foreachFast { q =>
        part("terms reachable from ")(joined(md, q.sourceNodes)(n => uri(md, n, prefixes)))
      }
      if (first) md.append("a value set that this page doesn't show")
    }

    /** List items for a query that finds the values of an enum in an ontology. */
    private def reachability(
        md: Markdown,
        q: ReachabilityQuery,
    )(using prefixes: PrefixMap): Unit = {
      q.sourceOntology.foreachFast { o =>
        md.startItem("Source ontology")
        uri(md, o, prefixes)
        md.endItem()
      }
      urisItem(md, "Source nodes", q.sourceNodes)
      urisItem(md, "Relationship types", q.relationshipTypes)
      if (q.isDirect) md.item("Direct only", "yes")
      if (q.includeSelf) md.item("Includes the source nodes", "yes")
      if (q.traverseUp) md.item("Upwards", "yes, to the ancestors of the source nodes")
    }

    private def typePage(md: Markdown, tv: TypeView, from: Option[Page]): Unit = {
      val t = tv._type
      uriItem(md, tv)
      t.typeof.foreachFast { parent =>
        md.startItem("Parent")
        typeLinkByName(md, parent.value, from)
        md.endItem()
      }
      if (t.unionOf.nonEmpty) {
        md.startItem("Union of")
        joined(md, t.unionOf)(m => typeLinkByName(md, m.value, from))
        md.endItem()
      }
      t.base.foreachFast(b => codeItem(md, "Base", b))
      t.pattern.foreachFast(p => codeItem(md, "Pattern", p))
      t.minimumValue.foreachFast(v => codeItem(md, "Minimum value", v.value))
      t.maximumValue.foreachFast(v => codeItem(md, "Maximum value", v.value))
      t.minimumLength.foreachFast(n => numberItem(md, "Minimum length", n))
      t.maximumLength.foreachFast(n => numberItem(md, "Maximum length", n))
      metadataItems(md, tv)
      md.endList()
      usages(md, tv, from)
      examples(md, t)
    }

    /** A table of the slots that have [[el]] as their range. */
    private def usages(md: Markdown, el: ElementView[?, ?], from: Option[Page]): Unit = {
      val usages = site.usagesOf(el)
      if (usages.nonEmpty) {
        md.heading(2, "Usages")
        md.tableHeader("Used by", "Slot", "Cardinality")
        usages.foreach { usage =>
          md.startRow()
          link(md, usage.classView, from)
          md.nextCell()
          slotName(md, usage.classView, usage.attribute, from)
          md.nextCell()
          cardinality(md, usage.attribute.slotView.slot)
          md.endRow()
        }
        md.endList()
      }
    }

    /** List items for the constraints of a slot. */
    private def slotItems(md: Markdown, slot: SlotDefinition): Unit = {
      if (slot.identifier) md.item("Identifier", "yes")
      if (slot.key) md.item("Key", "yes")
      if (slot.designatesType) md.item("Type designator", "yes")
      if (slot.recommended) md.item("Recommended", "yes")
      if (slot.inlinedAsList) md.item("Inlined", "yes, as a list")
      else if (slot.inlined) md.item("Inlined", "yes")
      slot.equalsString.foreachFast(v => codeItem(md, "Equals", v))
      slot.pattern.foreachFast(p => codeItem(md, "Pattern", p))
      slot.minimumValue.foreachFast(v => codeItem(md, "Minimum value", v.value))
      slot.maximumValue.foreachFast(v => codeItem(md, "Maximum value", v.value))
      slot.minimumLength.foreachFast(n => numberItem(md, "Minimum length", n))
      slot.maximumLength.foreachFast(n => numberItem(md, "Maximum length", n))
      slot.ifabsent.foreachFast(v => codeItem(md, "Default", v))
    }

    /** List items for the names of [[el]] other than its name in the schema, which is in the
      * heading:
      *   - its title, a name for people to read,
      *   - its canonical name, the end of its default URI, if it is different,
      *   - for a slot, its name in data such as JSON, if it has an `alias`,
      *   - its other names, from `aliases`, which are only for people and searches,
      *   - the names that generated code gives it, if any of them is different.
      */
    private def nameItems(md: Markdown, el: ElementView[?, ?]): Unit = {
      val m = meta(el)
      val name = el.name
      m.title.localized.foreachFast { t =>
        md.startItem("Title")
        md.text(t)
        md.endItem()
      }
      if (el.canonicalName != name) codeItem(md, "Canonical name", el.canonicalName)
      el match {
        case s: SlotView if s.aliasedName != name => codeItem(md, "Name in data", s.aliasedName)
        case _ =>
      }
      if (m.aliases.nonEmpty) {
        md.startItem("Other names")
        joined(md, m.aliases)(a => md.text(a.localized))
        md.endItem()
      }
      val names = codeNames(el)
      if (names.exists(_._2 != name)) {
        md.startItem("Names in code")
        // Targets that give the same name are listed together, like `PersonName` (Scala, Python).
        val targets = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[String]]
        names.foreach((target, codeName) =>
          targets.getOrElseUpdate(codeName, mutable.ListBuffer.empty).addOne(target),
        )
        joined(md, targets) { (codeName, ts) =>
          md.code(codeName)
          md.append(" (")
          md.append(ts.mkString(", "))
          md.append(')')
        }
        md.endItem()
      }
    }

    /** The names that the code generators give [[el]], as pairs of target language and name. Types
      * are left out, as the generated code mostly uses built-in types for them. So is an enum in a
      * language that has no type for it.
      */
    private def codeNames(el: ElementView[?, ?]): Seq[(String, String)] = el match {
      case cv: ClassView => codeTargets.map(t => (t.language, t.renamer.className(cv)))
      case ev: EnumView =>
        codeTargets.filter(_.definesEnum(ev)).map(t => (t.language, t.renamer.enumName(ev)))
      case s: SlotView =>
        codeTargets.map { t =>
          // Python fields are named after the slot's name in data, made a valid identifier.
          if (t.renamer eq PydanticRenamer)
            (t.language, PydanticRenamer.attribute(s.aliasedName, pythonNames))
          else (t.language, t.renamer.slotName(s))
        }
      case _ => Nil
    }

    /** The names that the Python generator gives its classes, class unions, enums and document
      * type, which fields do not take. It prunes as the documentation does.
      */
    private lazy val pythonNames: Set[String] =
      PydanticGenerator().moduleNames(PydanticGenerator.Options(pruningMode = options.pruningMode))

    /** List items for the metadata that every element can have. */
    private def metadataItems(md: Markdown, el: ElementView[?, ?]): Unit = {
      val m = meta(el)
      given PrefixMap = el.definingPrefixes
      urisItem(md, "Exact mappings", m.exactMappings)
      urisItem(md, "Close mappings", m.closeMappings)
      urisItem(md, "Related mappings", m.relatedMappings)
      urisItem(md, "Narrow mappings", m.narrowMappings)
      urisItem(md, "Broad mappings", m.broadMappings)
      urisItem(md, "See also", m.seeAlso)
      if (el.definingSchema ne sv.root) codeItem(md, "Defined in", el.definingSchema.name)
    }

    /** The list item with the URI of [[el]]. */
    private def uriItem(md: Markdown, el: ElementView[?, ?]): Unit = {
      md.startItem("URI")
      uri(md, el.uri, el.definingPrefixes)
      md.endItem()
    }

    private def urisItem(md: Markdown, label: String, uris: Seq[UriOrCurie])(using
        r: PrefixMap,
    ): Unit =
      if (uris.nonEmpty) {
        md.startItem(label)
        joined(md, uris)(u => uri(md, u, r))
        md.endItem()
      }

    private def examples(md: Markdown, el: CommonMetadata): Unit =
      if (el.examples.exists(_.value.isDefined)) {
        md.heading(2, "Examples")
        md.tableHeader("Value", "Description")
        el.examples.foreach { example =>
          example.value.foreachFast { value =>
            md.startRow()
            md.code(value)
            md.nextCell()
            example.valueDescription.localized.foreachFast(md.allOnOneLine)
            md.endRow()
          }
        }
        md.endList()
      }

    private def deprecation(md: Markdown, el: CommonMetadata): Unit =
      el.deprecated.localized.foreachFast(text => md.note("Deprecated", text))

    /** The range of a slot as it is in a class: links to the range, or to each range in its
      * `any_of`, `exactly_one_of` or `all_of`.
      */
    private def range(md: Markdown, av: AttributeView, from: Option[Page]): Unit =
      if (hasExpressionRanges(av.slotView.slot)) expressionRanges(md, av.slotView.slot, from)
      else {
        av match {
          case _: AnyView => md.append("Any")
          case a: ClassReferenceAttributeView =>
            link(md, a.classView, from)
            md.append(" (reference)")
          case a: ClassInlineAttributeView => link(md, a.classView, from)
          case a: TypeAttributeView => link(md, a.typeView, from)
          case a: EnumAttributeView => link(md, a.enumView, from)
        }
      }

    /** The range of a top-level slot, which might not resolve until a class uses the slot. */
    private def slotRange(md: Markdown, s: SlotView, from: Option[Page]): Unit =
      if (hasExpressionRanges(s.slot)) expressionRanges(md, s.slot, from)
      else linkByAnyName(md, s.derivedRange.value, from)

    private def hasExpressionRanges(slot: SlotDefinition): Boolean =
      hasRanges(slot.anyOf) || hasRanges(slot.exactlyOneOf) || hasRanges(slot.allOf)

    /** Links to the ranges in the `any_of` of [[slot]], joined with "or", and to those in its
      * `exactly_one_of` and `all_of`, after "exactly one of" and "all of". Each list that has
      * ranges is written, separated by "; ".
      */
    private def expressionRanges(md: Markdown, slot: SlotDefinition, from: Option[Page]): Unit = {
      var first = true
      def group(prefix: String, exprs: Seq[AnonymousSlotExpression], separator: String): Unit =
        if (hasRanges(exprs)) {
          if (!first) md.append("; ")
          md.append(prefix)
          var firstRange = true
          exprs.foreach { expr =>
            expr.range.foreachFast { r =>
              if (!firstRange) md.append(separator)
              linkByAnyName(md, r.value, from)
              firstRange = false
            }
          }
          first = false
        }
      group("", slot.anyOf, " or ")
      group("exactly one of ", slot.exactlyOneOf, ", ")
      group("all of ", slot.allOf, ", ")
    }

    /** The name of a slot of [[cv]], linked to the slot's page if it is a documented top-level
      * slot, and its name in data if it has an `alias`.
      */
    private def slotName(
        md: Markdown,
        cv: ClassView,
        av: AttributeView,
        from: Option[Page],
    ): Unit = {
      val slot = av.slotView
      val name = slot.name
      val page = if (site.isTopLevelSlot(cv, name)) site.slotPage(name) else None
      page.foldFast(md.text(name))(p => pageLink(md, name, from, p))
      if (slot.aliasedName != name) {
        md.append(" (")
        md.code(slot.aliasedName)
        md.append(" in data)")
      }
    }

    private def slotMarker(slot: SlotDefinition): String =
      if (slot.identifier) " (identifier)"
      else if (slot.key) " (key)"
      else if (slot.designatesType) " (type designator)"
      else ""

    /** A link to the page about [[el]], or its name as code if it is not documented. */
    private def link(md: Markdown, el: ElementView[?, ?], from: Option[Page]): Unit =
      site.pageOf(el).foldFast(md.code(el.name))(p => pageLink(md, el.name, from, p))

    /** A link to the page [[to]], with [[label]] as its text. */
    private def pageLink(md: Markdown, label: String, from: Option[Page], to: Page): Unit = {
      md.append('[')
      md.text(label)
      md.append("](")
      site.appendHref(md, from, to)
      md.append(')')
    }

    private def classLinkByName(md: Markdown, name: String, from: Option[Page]): Unit =
      sv.classes.get(name).foldFast(md.code(name))(cv => link(md, cv, from))

    private def slotLinkByName(md: Markdown, name: String, from: Option[Page]): Unit =
      sv.slotDefinitions.get(name).foldFast(md.code(name))(s => link(md, s, from))

    private def enumLinkByName(md: Markdown, name: String, from: Option[Page]): Unit =
      sv.enums.get(name).foldFast(md.code(name))(ev => link(md, ev, from))

    private def typeLinkByName(md: Markdown, name: String, from: Option[Page]): Unit =
      sv.types.get(name).foldFast(md.code(name))(tv => link(md, tv, from))

    private def linkByAnyName(md: Markdown, name: String, from: Option[Page]): Unit =
      sv.elements.get(name).foldFast(md.code(name)) {
        case cv: ClassView if cv.isAny => md.append("Any")
        case el => link(md, el, from)
      }
  }
}

object DocsGenerator {

  /** Options for [[DocsGenerator]].
    *
    * @param pruningMode
    *   Which classes to document, schemaRoot by default (classes reachable from any class defined
    *   in the root schema). The enums, types and slots these classes use are documented with them.
    * @param metadataLanguage
    *   Which language to use for titles and descriptions.
    * @param singleFile
    *   Whether to write all pages as sections of one document, `docs.md`, instead of a file for
    *   each page.
    */
  final case class Options(
      pruningMode: PruningMode = PruningMode.schemaRoot,
      metadataLanguage: String = "en",
      singleFile: Boolean = false,
  ) extends HasMetadataLanguage

  /** A code generator whose names element pages show.
    *
    * @param language
    *   The language it writes
    * @param renamer
    *   How it names classes, slots and enums
    * @param definesEnum
    *   Whether it has a type for an enum
    */
  private final case class CodeTarget(
      language: String,
      renamer: Renamer,
      definesEnum: EnumView => Boolean,
  )

  private val codeTargets: Seq[CodeTarget] = Seq(
    CodeTarget("Scala", ScalaRenamer, ScalaGenerator.definesEnum),
    CodeTarget("TypeScript", TypeScriptRenamer, _ => true),
    CodeTarget("Python", PydanticRenamer, PydanticGenerator.definesEnum),
    CodeTarget("GraphQL", GraphQlRenamer, _ => true),
  )

  /** The metadata of [[el]], which every kind of element has. */
  private def meta(el: ElementView[?, ?]): CommonMetadata = el.inner.asInstanceOf[Element]

  /** Whether one of [[exprs]] has a range. */
  private def hasRanges(exprs: Seq[AnonymousSlotExpression]): Boolean =
    exprs.exists(_.range.isDefined)

  /** The first paragraph of the description of [[el]], on one line, to fit in a table cell. */
  private def summary(md: Markdown, el: CommonMetadata)(using MetadataLanguage): Unit =
    el.description.localized.foreachFast(md.oneLine)

  /** How many values a slot takes: `1`, `0..1`, or a range like `0..*` if it is multivalued. */
  private def cardinality(md: Markdown, slot: SlotDefinition): Unit =
    if (slot.multivalued) {
      slot.exactCardinality.foldFast {
        md.number(slot.minimumCardinality.getOrElseFast(if (slot.required) 1 else 0))
        md.append("..")
        slot.maximumCardinality.foldFast(md.append('*'))(md.number)
      }(md.number)
    } else md.append(if (slot.required) "1" else "0..1")

  /** A list item with a bold label and [[value]] as code. */
  private def codeItem(md: Markdown, label: String, value: String): Unit = {
    md.startItem(label)
    md.code(value)
    md.endItem()
  }

  /** A list item with a bold label and [[n]]. */
  private def numberItem(md: Markdown, label: String, n: Int): Unit = {
    md.startItem(label)
    md.number(n)
    md.endItem()
  }

  /** [[items]] written with [[write]], separated by commas. */
  private def joined[V](md: Markdown, items: Iterable[V])(write: V => Unit): Unit = {
    var first = true
    items.foreach { item =>
      if (!first) md.append(", ")
      write(item)
      first = false
    }
  }

  /** A URI or CURIE as code, shown as a CURIE when it has a known prefix, and linked to the full
    * URI when that is a web address.
    */
  private def uri(md: Markdown, u: UriOrCurie, prefixes: PrefixMap): Unit = {
    val uri = u match {
      case x: Uri => x.original
      case _ => null
    }
    val curie = UriOrCurie.compacted(u, prefixes)
    if ((uri ne null) && isLinkable(uri)) {
      md.append('[')
      md.code(curie)
      md.append("](")
      md.append(uri)
      md.append(')')
    } else md.code(curie)
  }

  /** Whether [[uri]] is a web address that can be the target of a Markdown link as it is. */
  private def isLinkable(uri: String): Boolean =
    (uri.startsWith("https://") || uri.startsWith("http://")) &&
      !uri.exists(c => c <= ' ' || c == '(' || c == ')' || c == '<' || c == '>')
}

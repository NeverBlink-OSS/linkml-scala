package eu.neverblink.linkml.generator.docs

import eu.neverblink.linkml.generator.util.PruningMode
import eu.neverblink.linkml.metamodel.{Element, SlotDefinition}
import eu.neverblink.linkml.rdf.io.CharSink
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.Reference
import eu.neverblink.linkml.schemaview.*

import scala.collection.mutable

/** Which elements the documentation covers, what their pages are called, and how they are used.
  *
  * @param pruningMode
  *   Which classes to document. The enums and types they use are documented with them, and so are
  *   the slots they have.
  * @param singleFile
  *   Whether all pages are sections of one document, which changes how they link to each other
  */
final class DocsSite private[docs] (pruningMode: PruningMode, singleFile: Boolean)(using
    sv: SchemaView,
) {
  import DocsSite.*

  private val query = pruningMode.derivedQuery(false, true)

  /** The documented classes, in the common order. `linkml:Any` is left out, as it is not a class
    * that a user defines.
    */
  val classes: Seq[ClassView] = sv.sortedClasses.filter(cv => query.reachable(cv) && !cv.isAny)

  val enums: Seq[EnumView] = sorted(sv.enums.values.filter(query.reachable))

  val types: Seq[TypeView] = sorted(sv.types.values.filter(query.reachable))

  /** The class each class is a direct `is_a` child of, and the classes that use it as a mixin. */
  private val children = mutable.HashMap.empty[String, mutable.ListBuffer[ClassView]]
  private val mixinUsers = mutable.HashMap.empty[String, mutable.ListBuffer[ClassView]]

  /** Usages of classes, enums and types as a range, keyed by the range's name. */
  private val classUsages = mutable.HashMap.empty[String, mutable.ListBuffer[Usage]]
  private val enumUsages = mutable.HashMap.empty[String, mutable.ListBuffer[Usage]]
  private val typeUsages = mutable.HashMap.empty[String, mutable.ListBuffer[Usage]]

  /** Usages of top-level slots, keyed by the slot's name. */
  private val slotUsages = mutable.HashMap.empty[String, mutable.ListBuffer[Usage]]

  /** Names of the top-level slots that a documented class has, either its own or inherited. */
  private val usedSlots = mutable.HashSet.empty[String]

  classes.foreach { cv =>
    cv.cls.isA.foreachFast(parent => add(children, parent.value, cv))
    cv.cls.mixins.foreach(mixin => add(mixinUsers, mixin.value, cv))
    cv.sortedAttributeViews.foreach { av =>
      val name = av.slotView.name
      val topLevel = isTopLevelSlot(cv, name)
      if (topLevel) usedSlots.add(name)
      if (declares(cv, name)) {
        val usage = Usage(cv, av)
        if (topLevel) add(slotUsages, name, usage)
        addRangeUsage(av.slotView, usage)
      }
    }
  }

  /** The documented top-level slots: all of them if nothing is pruned, otherwise the ones that a
    * documented class has.
    */
  val slots: Seq[SlotView] = sorted(
    sv.slotDefinitions.values.filter(s => pruningMode == PruningMode.skip || usedSlots(s.name)),
  )

  private val classPages = pagesOf(PageKind.classes, classes)
  private val slotPages = pagesOf(PageKind.slots, slots)
  private val enumPages = pagesOf(PageKind.enums, enums)
  private val typePages = pagesOf(PageKind.types, types)

  /** The page about [[el]], if it is documented. */
  def pageOf(el: ElementView[?, ?]): Option[Page] = el match {
    case cv: ClassView => classPages.get(cv.name)
    case s: SlotView => slotPages.get(s.name)
    case ev: EnumView => enumPages.get(ev.name)
    case tv: TypeView => typePages.get(tv.name)
    case _ => None
  }

  /** The page about the top-level slot named [[name]], if it is documented. */
  private[docs] def slotPage(name: String): Option[Page] = slotPages.get(name)

  /** Write the link from the page [[from]] to [[to]]. `None` stands for the index page. */
  private[docs] def appendHref(out: CharSink, from: Option[Page], to: Page): Unit =
    if (singleFile) {
      out.append('#')
      to.appendAnchor(out)
    } else {
      from match {
        case Some(page) =>
          if (page.kind == to.kind) to.appendFileName(out)
          else {
            out.append("../")
            to.appendPath(out)
          }
        case _ => to.appendPath(out)
      }
    }

  /** The documented classes whose `is_a` is [[cv]]. */
  def childrenOf(cv: ClassView): Seq[ClassView] = children.get(cv.name).foldFast(Nil)(_.toList)

  /** The documented classes that list [[cv]] in their `mixins`. */
  def mixinUsersOf(cv: ClassView): Seq[ClassView] =
    mixinUsers.get(cv.name).foldFast(Nil)(_.toList)

  /** The slots of documented classes whose range is [[el]]. Only the classes that declare a slot,
    * or change it with `slot_usage`, are listed, and not their subclasses that inherit it.
    */
  def usagesOf(el: ElementView[?, ?]): Seq[Usage] = {
    val usages = el match {
      case cv: ClassView => classUsages.get(cv.name)
      case s: SlotView => slotUsages.get(s.name)
      case ev: EnumView => enumUsages.get(ev.name)
      case tv: TypeView => typeUsages.get(tv.name)
      case _ => None
    }
    usages.foldFast(Nil)(_.toList)
  }

  /** The nearest ancestor of [[cv]] that declares the slot [[name]], or `None` if [[cv]] declares
    * it itself.
    */
  def inheritedFrom(cv: ClassView, name: String): Option[ClassView] =
    if (declaresInSlots(cv, name)) None
    else cv.ancestorsWithSelf.find(anc => (anc ne cv) && declaresInSlots(anc, name))

  /** Whether the slot [[name]] of [[cv]] is a top-level slot, rather than an attribute. */
  private[docs] def isTopLevelSlot(cv: ClassView, name: String): Boolean =
    sv.slotDefinitions.contains(name) && !cv.isSlotFromAttributes(
      new Reference[SlotDefinition](name),
    )

  /** Whether [[cv]] lists the slot [[name]] in its `slots` or `attributes`. */
  private def declaresInSlots(cv: ClassView, name: String): Boolean =
    cv.cls.attributes.contains(name) || cv.cls.slots.exists(_.value == name)

  /** Whether [[cv]] declares the slot [[name]], or changes it with `slot_usage`. */
  private def declares(cv: ClassView, name: String): Boolean =
    declaresInSlots(cv, name) || cv.cls.slotUsage.contains(name)

  /** Record [[usage]] for the range of [[slot]], and for each range in its `any_of`. */
  private def addRangeUsage(slot: SlotView, usage: Usage): Unit = {
    val seen = mutable.HashSet.empty[String]
    def addRange(name: String): Unit =
      if (seen.add(name)) {
        sv.elements.get(name).foreachFast {
          case cv: ClassView => if (!cv.isAny) add(classUsages, name, usage)
          case _: EnumView => add(enumUsages, name, usage)
          case _: TypeView => add(typeUsages, name, usage)
          case _ =>
        }
      }
    addRange(slot.derivedRange.value)
    slot.slot.anyOf.foreach(expr => expr.range.foreachFast(r => addRange(r.value)))
  }

  private def add[V](map: mutable.HashMap[String, mutable.ListBuffer[V]], key: String, v: V): Unit =
    map.getOrElseUpdate(key, mutable.ListBuffer.empty).addOne(v)

  private def sorted[V <: ElementView[?, ?]](elements: Iterable[V]): Seq[V] =
    elements.toVector.sortBy(el => sv.elementOrder(el.inner.asInstanceOf[Element]))

  /** Pick a file name for each element, keyed by the element's name. */
  private def pagesOf(kind: PageKind, elements: Seq[ElementView[?, ?]]): Map[String, Page] = {
    val taken = mutable.HashSet.empty[String]
    val pages = Map.newBuilder[String, Page]
    elements.foreach { el =>
      val base = DocsSite.fileStem(el.name)
      var stem = base
      var n = 2
      // Compare without case, so that `Person` and `person` do not overwrite each other on macOS
      // and Windows.
      while (!taken.add(DocsSite.lowerCase(stem))) {
        stem = base + "_" + n
        n += 1
      }
      pages.addOne((el.name, Page(kind, stem)))
    }
    pages.result()
  }
}

object DocsSite {

  /** The kinds of element that get a page, with the folder their pages go in, the label of their
    * headings, and the start of their anchors.
    */
  enum PageKind(val dir: String, val label: String, val anchorPrefix: String):
    case classes extends PageKind("classes", "Class", "class-")
    case slots extends PageKind("slots", "Slot", "slot-")
    case enums extends PageKind("enums", "Enum", "enum-")
    case types extends PageKind("types", "Type", "type-")

  /** The page about one element.
    *
    * @param kind
    *   What kind of element the page is about
    * @param stem
    *   The file name without `.md`, unique within [[kind]] even on file systems that ignore case
    */
  final case class Page(kind: PageKind, stem: String):
    /** The page's file, relative to the folder that holds `index.md`. */
    def path: String = kind.dir + "/" + stem + ".md"

    /** Write the page's file, relative to the folder that holds `index.md`. */
    private[docs] def appendPath(out: CharSink): Unit = {
      out.append(kind.dir)
      out.append('/')
      appendFileName(out)
    }

    /** Write the page's file name, without its folder. */
    private[docs] def appendFileName(out: CharSink): Unit = {
      out.append(stem)
      out.append(".md")
    }

    /** Write the page's HTML anchor, for when all pages are sections of one document. */
    private[docs] def appendAnchor(out: CharSink): Unit = {
      out.append(kind.anchorPrefix)
      var i = 0
      while (i < stem.length) {
        out.append(Character.toLowerCase(stem.charAt(i)))
        i += 1
      }
    }

  /** A class that declares a slot or changes it with `slot_usage`.
    *
    * @param classView
    *   The class
    * @param attribute
    *   The slot as it is in [[classView]], with the class' `slot_usage` applied
    */
  final case class Usage(classView: ClassView, attribute: AttributeView)

  /** File names that Windows does not allow, whatever the extension. */
  private val reserved = Set("con", "prn", "aux", "nul") ++
    (1 to 9).flatMap(i => Seq("com".concat(i.toString), "lpt".concat(i.toString)))

  /** [[name]] as a file name without the extension. Letters, digits, `_`, `-` and `.` are kept, and
    * anything else becomes `_`, so that the name works in a link without encoding.
    */
  private[docs] def fileStem(name: String): String = {
    val sb = new java.lang.StringBuilder(name.length + 1)
    var i = 0
    while (i < name.length) {
      val c = name.charAt(i)
      sb.append(if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') c else '_')
      i += 1
    }
    val stem = sb.toString
    if (stem.isEmpty || stem.charAt(0) == '.' || reserved.contains(lowerCase(stem)))
      "_".concat(stem)
    else stem
  }

  /** [[s]] in lower case. Unlike `toLowerCase`, this doesn't depend on the default locale. In
    * Turkish, `toLowerCase` turns `Item` into `ıtem`, which wouldn't clash with `item`.
    */
  private[docs] def lowerCase(s: String): String = {
    val sb = new java.lang.StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      sb.append(Character.toLowerCase(s.charAt(i)))
      i += 1
    }
    sb.toString
  }
}

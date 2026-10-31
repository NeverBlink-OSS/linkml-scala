package eu.neverblink.linkml.generator.docs

import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.schemaview.*

/** File names of documentation pages, without the `.md` extension. Base names work as file names
  * and in links without encoding, on any file system.
  */
trait DocsRenamer extends Renamer {

  /** A name that gives no base name, like `?`, would make a hidden `.md` file. */
  private def fix(baseName: String): String =
    if (baseName.isEmpty) "_"
    else baseName

  override def className(el: ClassView): String = fix(el.baseName)

  override def classAttributeName(el: ClassView, attr: SlotDefinition): String =
    slotName(el.derivedAttributes(attr.name))

  override def slotName(el: SlotView): String = fix(el.baseName)

  override def typeName(el: TypeView): String = fix(el.baseName)

  override def enumName(el: EnumView): String = fix(el.baseName)

  override def functionName(el: FunctionView): String = fix(el.baseName)

  /** Permissible values have no page of their own, so this name appears nowhere. */
  override def permissibleValueName(el: EnumView, pv: PermissibleValue): String =
    fix(Case.base(pv.text))
}

object DocsRenamer extends DocsRenamer

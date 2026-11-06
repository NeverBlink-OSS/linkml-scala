package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.schemaview.{ClassView, InlineType}

/** The type of a whole document, which the code generators add for a tree root whose `tree_root_as`
  * is not one object.
  */
object TreeRootDocument {

  /** Name of the document type, made of the generated name of the tree root. */
  def name(rootName: String): String = rootName.concat("Document")

  /** Doc comment of the document type. */
  def description(root: ClassView, inlineType: InlineType): String = inlineType match {
    case _: InlineType.plain.type => "A whole document: one " + root.name + "."
    case _: InlineType.optional.type => "A whole document: one " + root.name + " or null."
    case _: InlineType.list.type => "A whole document: a list of " + root.name + "."
    case _ => "A whole document: a dict of " + root.name + " by key."
  }
}

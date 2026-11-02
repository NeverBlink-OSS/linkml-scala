package eu.neverblink.ohi.cli

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.cli.generated.declaration
import eu.neverblink.ohi.model.generated.{Format, lock}

private[cli] object ProjectFormat {
  private val declared: LinkmlYamlCodec[declaration.Format] = LinkmlYamlCodec.derived
  private val published: LinkmlYamlCodec[Format] = LinkmlYamlCodec.derived
  private val locked: LinkmlYamlCodec[lock.Format] = LinkmlYamlCodec.derived

  /** Converts formats between generated roots that import the same LinkML enum. */
  def toModel(format: declaration.Format): Format = published.decode(declared.encode(format))

  def toDeclaration(format: Format): declaration.Format = declared.decode(published.encode(format))

  def toDeclaration(format: lock.Format): declaration.Format =
    declared.decode(locked.encode(format))

}

package millbuild

/** Hides a module's nested `native` module unless [[nativeEnabled]].
 */
trait NativeOptional extends mill.api.DynamicModule {
  def nativeEnabled: Boolean = NativeOptional.nativeEnabled

  override def moduleDirectChildren: Seq[mill.api.Module] =
    if nativeEnabled then super.moduleDirectChildren
    else super.moduleDirectChildren.filterNot(_.moduleSegments.last.value == "native")
}

object NativeOptional {
  def nativeEnabled: Boolean =
    sys.env.get("LINKML_NATIVE").exists(v => v == "1" || v.equalsIgnoreCase("true"))
}
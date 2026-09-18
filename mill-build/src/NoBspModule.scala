package millbuild

/** Trait to disable BSP for shared-code platform modules to speed up IDEs
  */
trait NoBspModule extends CommonModule {
  override def enableBsp: Boolean = false
}

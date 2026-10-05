package eu.neverblink.linkml.runtime

/** Class representing a reference to some type [[T]] that may be resolved by using an appropriate
  * [[ReferenceResolver]]
  * @param value
  *   String identifier of the referenced instance
  * @tparam T
  *   Type that this reference points to
  */
@flatten final case class Reference[+T](value: String):
  /** Resolve the reference using an implicitly provided [[ReferenceResolver]]
    * @param rr
    *   [[ReferenceResolver]] to resolve the reference with
    * @return
    *   The referenced instance if it exists, None otherwise
    */
  inline def resolve(using rr: ReferenceResolver): Option[T] = rr.resolve(this)

package eu.neverblink.linkml.schemaview

/** Enum defining how a LinkML type can be converted to an RDF IRI.
  *   - uri: value can be lifted directly
  *   - curie: value can be lifted by first expanding the CURIE
  *   - uriOrCurie: value should be treated as either a URI or CURIE, and lifted or expanded
  *     accordingly
  *   - implicitPrefix: value should be constructed using the `implicit_prefix` metaslot
  *   - base: value should be constructed using the default prefix / base, or treated as an RDF
  *     Literal instead
  */
enum SubjectType:
  case uri, curie, uriOrCurie, base
  case implicitPrefix(prefix: String)

  /** If a value has this SubjectType in the object position, and is not a reference, test whether
    * it should be an RDF IRI or an RDF Literal.
    *
    * @return
    *   true if this type should be an RDF IRI
    */
  def isIri: Boolean = this match {
    case SubjectType.base => false
    case _ => true
  }

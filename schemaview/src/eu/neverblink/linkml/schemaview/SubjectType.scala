package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.PrefixImpl

/** Enum defining the mapping between non-RDF values and RDF subject-position IRIs.
  *   - uri: value can be lifted directly as an IRI
  *   - curie: value can be lifted by first expanding the IRI
  *   - uriOrCurie: value should be treated as either a URI or CURIE
  *   - implicitPrefix: value should be lifted using the `implicit_prefix` metaslot
  *   - base: value should be lifted using the default prefix / base
  */
enum SubjectType:
  case uri, curie, uriOrCurie, base
  case implicitPrefix(prefix: PrefixImpl)

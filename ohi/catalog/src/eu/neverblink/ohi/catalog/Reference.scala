package eu.neverblink.ohi.catalog

/** Ontology references `namespace/name[@version]`, shared by the SDK and the ohi CLI. */
private[ohi] object Reference {
  private val Pattern = "(?U)([^/@\\s`]+)/([^/@\\s`]+)(?:@([^/@\\s+`]+))?".r

  /** Namespace, name and optional version when the whole input is a reference, else None. */
  def parse(input: String): Option[(String, String, Option[String])] =
    input match {
      case Pattern(namespace, name, version) => Some((namespace, name, Option(version)))
      case _ => None
    }

  /** Typesense filter for the releases of one ontology. */
  def filter(namespace: String, name: String): String =
    s"namespace:=`$namespace` && name:=`$name`"
}

package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.config.OwlImportConfig
import eu.neverblink.linkml.generator.owl.config.OwlImportConfigs.metadataSources
import eu.neverblink.linkml.metamodel.Annotation as LinkmlAnnotation
import eu.neverblink.linkml.rdf.{Iri, LanguageLiteral, Literal, Node, XmlSchema}
import eu.neverblink.linkml.runtime.*

import scala.collection.immutable.VectorMap
import scala.collection.mutable

/** Turns ontology annotations into LinkML metaslots where a mapping exists, and the rest into
  * `annotations`.
  *
  * @param annotationsOf
  *   Annotations by term IRI.
  */
private[owl] final class OwlAnnotationReader(
    annotationsOf: Map[String, Seq[Annotation]],
    ontologyAnnotations: Seq[Annotation],
    config: OwlImportConfig,
    names: OwlImportNames,
) {
  import OwlAnnotationReader.*

  private val sources: Seq[(Metadata.Field, Seq[String])] =
    config.metadataSources.flatMap((slot, properties) =>
      Metadata.byName.get(slot).map(_ -> properties.map(names.expand)),
    )

  /** The annotation properties of human-readable metaslots, such as title and description. */
  private val humanTextProperties: Set[String] =
    sources.collect { case (field, properties) if Metadata.humanText(field.name) => properties }
      .flatten.toSet

  /** The schema's `in_language`: the language tag of at least 90% of the human-readable text. Text
    * in that language is imported without the tag. Code examples, identifiers and other text with
    * no language are not counted.
    */
  val language: Option[String] = {
    val texts = (annotationsOf.values.flatten ++ ontologyAnnotations)
      .filter(a => humanTextProperties(a.property)).map(_.value).collect {
        case LanguageLiteral(_, l) => Some(l.toLowerCase)
        case Literal(_, XmlSchema.string) => None
      }.toSeq
    texts.flatten.groupBy(identity).maxByOption(_._2.size).collect {
      case (l, tagged) if tagged.size >= 0.9 * texts.size => l
    }
  }

  private def plainInLanguage(a: Annotation): Annotation = a.value match {
    case LanguageLiteral(v, l) if language.contains(l.toLowerCase) => a.copy(value = Literal(v))
    case _ => a
  }

  /** A text's language tag, or the schema's language for plain text. */
  private def languageOf(value: Node): Option[String] = value match {
    case LanguageLiteral(_, l) => Some(l)
    case Literal(_, XmlSchema.string) => language
    case _ => None
  }

  /** Whether every value has a language and no two share one. */
  private def oneLanguageEach(values: Seq[Annotation]): Boolean = {
    val languages = values.map(a => languageOf(a.value).map(_.toLowerCase))
    languages.forall(_.isDefined) && languages.distinct.size == languages.size
  }

  def documentation(iri: String): (Metadata, Map[String, LinkmlAnnotation]) =
    documentation(annotationsOf.getOrElse(iri, Nil))

  def documentation(annotations: Seq[Annotation]): (Metadata, Map[String, LinkmlAnnotation]) = {
    val (metadata, rest) = metadataOf(annotations)
    (metadata, linkmlAnnotations(rest))
  }

  /** Splits annotations into metadata and the leftovers.
    *
    * Each metaslot reads its properties in order. A single-valued one stops at the first property
    * with a value, or for text, takes one value per language. The values it takes, and any other
    * values with the same text, are not available to later metaslots.
    */
  private def metadataOf(annotations: Seq[Annotation]): (Metadata, Seq[Annotation]) = {
    val remaining = mutable.ArrayBuffer.from(annotations.map(plainInLanguage))
    var metadata = Metadata()
    sources.foreach { (field, properties) =>
      val single = singleValued(field.name)
      val values = mutable.ArrayBuffer.empty[String | LocalizedText | UriOrCurie]
      val texts = mutable.ArrayBuffer.empty[Annotation]
      properties.iterator.takeWhile(_ => !single || values.isEmpty).foreach { property =>
        val usable =
          remaining.filter(a => a.property == property && valueOf(field, a.value).isDefined)
        val chosen =
          if !single then usable.toSeq
          else if field.kind == Metadata.Kind.Text then oneTextInLanguages(usable.toSeq)
          else usable.take(1).toSeq
        chosen.foreach(remaining -= _)
        if field.kind == Metadata.Kind.Text && single && chosen.nonEmpty then
          values += textOf(chosen.map(_.value))
        else if field.kind == Metadata.Kind.Text then texts ++= chosen
        else values ++= chosen.flatMap(a => valueOf(field, a.value))
      }
      values ++= textsOf(texts.toSeq)
      if values.nonEmpty then {
        metadata = field.set(metadata, values.toSeq)
        // Drop other values with the same text, such as a comment that repeats the definition.
        val texts = values.flatMap {
          case t: String => Seq(t)
          case PlainText(t) => Seq(t)
          case MultilingualText(m) => m.values
          case u: UriOrCurie => Seq(u.original)
        }.toSet
        remaining.filterInPlace(a => !textIn(a.value).exists(texts))
      }
    }
    (metadata, remaining.toSeq)
  }

  private def valueOf(field: Metadata.Field, value: Node): Option[String | UriOrCurie] =
    (field.kind, value) match {
      case (Metadata.Kind.Flag, Literal(v, _)) => Option.when(v == "true" || v == "1")("true")
      case (Metadata.Kind.Flag, _) => None
      // An IRI that is neither a URL nor a CURIE, like `mailto:`, would be read back as a CURIE
      // with an unknown prefix, so leave it to the annotations.
      case (Metadata.Kind.Reference, Iri(v))
          if !v.contains("://") && !v.startsWith("urn:") &&
            names.curie(v, record = false).isInstanceOf[Uri] =>
        None
      case (Metadata.Kind.Reference, Iri(v)) => Some(names.curie(v))
      // Text is a reference only if it is an IRI or a CURIE with a known prefix.
      case (Metadata.Kind.Reference, Literal(v, _)) if v.nonEmpty && !v.exists(notInIri) =>
        if v.contains("://") || v.startsWith("urn:") then Some(v)
        else
          Some(names.expand(v)).filter(_ != v).map { iri =>
            names.ensurePrefix(iri)
            names.curie(iri)
          }
      case (Metadata.Kind.Reference, _) => None
      case (_, Iri(v)) => Some(v)
      case (_, other) => textIn(other)
    }

  private def oneTextInLanguages(values: Seq[Annotation]): Seq[Annotation] =
    if oneLanguageEach(values) then values else values.take(1)

  private def textOf(values: Seq[Node]): LocalizedText = values match {
    case Seq(Literal(v, _)) => PlainText(v)
    case Seq(Iri(v)) => PlainText(v)
    // Text in the schema's language is already plain, so this tag is a different one.
    case Seq(LanguageLiteral(v, l)) => MultilingualText(Map(l -> v))
    case many =>
      MultilingualText(many.flatMap(v => languageOf(v).zip(textIn(v))).toMap)
  }

  /** Values of a multivalued text metaslot, gathered from all its properties. Texts that each have
    * a different language become one multilingual text. Otherwise we can't tell which are
    * translations, so each becomes its own value.
    */
  private def textsOf(values: Seq[Annotation]): Seq[LocalizedText] =
    if values.sizeIs > 1 && oneLanguageEach(values) then Seq(textOf(values.map(_.value)))
    else values.map(a => textOf(Seq(a.value)))

  private def linkmlAnnotations(rest: Seq[Annotation]): Map[String, LinkmlAnnotation] =
    if !config.annotations then Map.empty
    else
      VectorMap.from(rest.groupBy(_.property).toSeq.sortBy(_._1).flatMap { (property, values) =>
        names.ensurePrefix(property)
        val tag = names.curie(property)
        // Dropping language tags can make two values equal, so remove duplicates.
        val texts = values.map(_.value).flatMap {
          case Iri(v) => Some(names.curie(v).original)
          case other => textIn(other)
        }.distinct
        Option.when(texts.nonEmpty) {
          val value =
            if texts.sizeIs == 1 then yamlString(texts.head)
            else LinkmlAny(texts.map(t => "- " + yamlString(t).value).mkString)
          tag.original -> LinkmlAnnotation(extensionTag = tag, extensionValue = value)
        }
      })
}

private[owl] object OwlAnnotationReader {

  private val singleValued = Set(
    "title",
    "description",
    "source",
    "created_by",
    "created_on",
    "last_updated_on",
    "modified_by",
    "status",
    "in_language",
    "deprecated",
    "license",
    "version",
  )

  /** Characters that IRIs and CURIEs cannot have. */
  private def notInIri(c: Char): Boolean = c.isWhitespace || "<>\"{}|\\^`".contains(c)

  private def textIn(node: Node): Option[String] = node match {
    case Literal(v, _) => Some(v)
    case LanguageLiteral(v, _) => Some(v)
    case _ => None
  }

  private def yamlString(value: String): LinkmlAny = LinkmlAny.text(value)
}

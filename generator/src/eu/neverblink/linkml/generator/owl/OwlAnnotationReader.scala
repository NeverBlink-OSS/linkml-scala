package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.options.OwlImportConfig
import eu.neverblink.linkml.generator.owl.config.OwlImportConfigs.metadataSources
import eu.neverblink.linkml.metamodel.Annotation as LinkmlAnnotation
import eu.neverblink.linkml.rdf.{Iri, IntBuffer, LanguageLiteral, Literal, Node, XmlSchema}
import eu.neverblink.linkml.runtime.*

import eu.neverblink.linkml.generator.util.IntBox

import scala.collection.immutable.VectorMap
import scala.collection.mutable

/** Turns ontology annotations into LinkML metaslots where a mapping exists, and the rest into
  * `annotations`.
  *
  * @param annotationsOf
  *   Annotations by term IRI.
  */
private[owl] final class OwlAnnotationReader(
    annotationsOf: collection.Map[String, collection.Seq[Annotation]],
    ontologyAnnotations: Seq[Annotation],
    config: OwlImportConfig,
    names: OwlImportNames,
) {
  import OwlAnnotationReader.*

  /** The metaslots to fill, each with its annotation properties in order of preference. */
  private val sources = {
    val sources =
      mutable.ArrayBuffer.empty[(field: Metadata.Field, properties: mutable.ArrayBuffer[String])]
    config.metadataSources.foreach { source =>
      val field = Metadata.byName.getOrElse(source._1, null)
      if (field ne null) {
        val properties = mutable.ArrayBuffer.empty[String]
        source._2.foreach(p => properties.addOne(names.expand(p)))
        sources.addOne((field = field, properties = properties))
      }
    }
    sources
  }

  /** The annotation properties of human-readable metaslots, such as title and description. */
  private val humanTextProperties: mutable.HashSet[String] = {
    val properties = mutable.HashSet.empty[String]
    sources.foreach(s => if (Metadata.humanText(s.field.name)) properties.addAll(s.properties))
    properties
  }

  /** The schema's `in_language`: the language tag of at least 90% of the human-readable text. Text
    * in that language is imported without the tag. Code examples, identifiers and other text with
    * no language are not counted.
    */
  val language: Option[String] = {
    val counts = mutable.HashMap.empty[String, IntBox]
    var texts = 0
    def count(a: Annotation): Unit =
      if (humanTextProperties.contains(a.property)) {
        a.value match {
          case l: LanguageLiteral =>
            texts += 1
            counts.getOrElseUpdate(l.language.toLowerCase, new IntBox).value += 1
          case l: Literal => if (l.datatype == XmlSchema.string) texts += 1
          case _ =>
        }
      }
    annotationsOf.valuesIterator.foreach(_.foreach(count))
    ontologyAnnotations.foreach(count)
    var best: String = null
    var bestCount = 0
    counts.foreachEntry { (tag, count) =>
      if (count.value > bestCount) {
        best = tag
        bestCount = count.value
      }
    }
    if ((best ne null) && bestCount >= 0.9 * texts) Some(best) else None
  }

  /** [[language]], or `null` if there is none. */
  private val languageTag: String = language.orNull

  private def plainInLanguage(a: Annotation): Annotation = a.value match {
    case l: LanguageLiteral =>
      if ((languageTag ne null) && languageTag == l.language.toLowerCase)
        a.copy(value = Literal(l.value))
      else a
    case _ => a
  }

  /** A text's language tag, or the schema's language for plain text, or `null` if it has none. */
  private def languageOf(value: Node): String = value match {
    case l: LanguageLiteral => l.language
    case l: Literal => if (l.datatype == XmlSchema.string) languageTag else null
    case _ => null
  }

  /** Whether every value has a language and no two share one. */
  private def oneLanguageEach(values: collection.Seq[Annotation]): Boolean = {
    val seen = mutable.HashSet.empty[String]
    values.forall { a =>
      val language = languageOf(a.value)
      (language ne null) && seen.add(language.toLowerCase)
    }
  }

  def documentation(iri: String): Documentation =
    documentation(annotationsOf.getOrElse(iri, Nil))

  def documentation(annotations: collection.Seq[Annotation]): Documentation = {
    val rest = mutable.ArrayBuffer.empty[Annotation]
    val metadata = metadataOf(annotations, rest)
    (metadata = metadata, annotations = linkmlAnnotations(rest))
  }

  /** Splits annotations into metadata and the leftovers, which it adds to `remaining`.
    *
    * Each metaslot reads its properties in order. A single-valued one stops at the first property
    * with a value, or for text, takes one value per language. The values it takes, and any other
    * values with the same text, are not available to later metaslots.
    */
  private def metadataOf(
      annotations: collection.Seq[Annotation],
      remaining: mutable.ArrayBuffer[Annotation],
  ): Metadata = {
    annotations.foreach(a => remaining.addOne(plainInLanguage(a)))
    val n = remaining.length
    // The annotations that were taken, instead of removing them one by one.
    val used = new java.util.BitSet(n)
    var metadata = Metadata.empty
    val values = mutable.ArrayBuffer.empty[String | LocalizedText | UriOrCurie]
    val texts = mutable.ArrayBuffer.empty[Annotation]
    val chosen = mutable.ArrayBuffer.empty[Annotation]
    val chosenIndices = new IntBuffer
    sources.foreach { source =>
      val field = source.field
      val properties = source.properties
      val single = singleValued(field.name)
      val isText = field.kind == Metadata.Kind.Text
      values.clear()
      texts.clear()
      var j = 0
      while (j < properties.length && (!single || values.isEmpty)) {
        val property = properties(j)
        chosen.clear()
        chosenIndices.clear()
        var i = 0
        while (i < n) {
          val a = remaining(i)
          if (!used.get(i) && a.property == property && (valueOf(field, a.value) ne null)) {
            chosen.addOne(a)
            chosenIndices.add(i)
          }
          i += 1
        }
        // A single-valued metaslot takes one value, or for text, one per language.
        if (single && chosen.length > 1 && (!isText || !oneLanguageEach(chosen))) {
          chosen.dropRightInPlace(chosen.length - 1)
          chosenIndices.truncate(1)
        }
        chosenIndices.foreach(used.set)
        if (isText) {
          if (single) {
            if (chosen.nonEmpty) values.addOne(textOf(chosen))
          } else texts.addAll(chosen)
        } else {
          chosen.foreach { a =>
            val value = valueOf(field, a.value)
            if (value ne null) values.addOne(value)
          }
        }
        j += 1
      }
      addTexts(texts, values)
      if (values.nonEmpty) {
        metadata = field.set(metadata, Vector.from(values))
        // Drop other values with the same text, such as a comment that repeats the definition.
        val taken = mutable.HashSet.empty[String]
        values.foreach {
          case t: String => taken.addOne(t)
          case t: PlainText => taken.addOne(t.value)
          case t: MultilingualText => taken.addAll(t.mapping.valuesIterator)
          case u: UriOrCurie => taken.addOne(u.original)
        }
        var i = 0
        while (i < n) {
          if (!used.get(i)) {
            val text = textIn(remaining(i).value)
            if ((text ne null) && taken.contains(text)) used.set(i)
          }
          i += 1
        }
      }
    }
    // Keep only the annotations that were not taken, in order.
    var kept = 0
    var i = 0
    while (i < n) {
      if (!used.get(i)) {
        remaining(kept) = remaining(i)
        kept += 1
      }
      i += 1
    }
    remaining.dropRightInPlace(n - kept)
    metadata
  }

  /** The value of an annotation for a metaslot, or `null` if it can't be one. */
  private def valueOf(field: Metadata.Field, value: Node): String | UriOrCurie =
    field.kind match {
      case Metadata.Kind.Flag =>
        value match {
          case l: Literal => if (l.value == "true" || l.value == "1") "true" else null
          case _ => null
        }
      case Metadata.Kind.Reference =>
        value match {
          // An IRI that can't be a URI in the schema is left to the annotations, as text
          case i: Iri => if (names.isWritable(i.value)) names.uri(i.value) else null
          // Text is a reference only if it is a URL, a URN or a CURIE with a known prefix, and its
          // expansion can be a URI in the schema.
          case l: Literal =>
            val v = l.value
            if (v.isEmpty) null
            else if (v.contains("://") || v.startsWith("urn:")) {
              if (names.isWritable(v)) names.uri(v) else null
            } else {
              val iri = names.expand(v)
              if (!iri.equals(v) && names.isWritable(iri)) {
                names.ensurePrefix(iri)
                names.uri(iri)
              } else null
            }
          case _ => null
        }
      case _ =>
        value match {
          case i: Iri => i.value
          case other => textIn(other)
        }
    }

  /** One text from the values of annotations, with a language for each if there are several. */
  private def textOf(values: collection.Seq[Annotation]): LocalizedText = {
    val single = if (values.length == 1) singleText(values.head.value) else null
    if (single ne null) single
    else {
      val byLanguage = Map.newBuilder[String, String]
      values.foreach { a =>
        val language = languageOf(a.value)
        val text = textIn(a.value)
        if ((language ne null) && (text ne null)) byLanguage.addOne(language -> text)
      }
      MultilingualText(byLanguage.result())
    }
  }

  /** The text of a single value, or `null` if it is not a literal or an IRI. Text in the schema's
    * language is already plain, so a language tag is a different one.
    */
  private def singleText(value: Node): LocalizedText = value match {
    case l: Literal => PlainText(l.value)
    case i: Iri => PlainText(i.value)
    case l: LanguageLiteral => MultilingualText(Map(l.language -> l.value))
    case _ => null
  }

  /** Adds the values of a multivalued text metaslot, gathered from all its properties, to `out`.
    * Texts that each have a different language become one multilingual text. Otherwise we can't
    * tell which are translations, so each becomes its own value.
    */
  private def addTexts(
      values: mutable.ArrayBuffer[Annotation],
      out: mutable.ArrayBuffer[String | LocalizedText | UriOrCurie],
  ): Unit =
    if (values.length > 1 && oneLanguageEach(values)) out.addOne(textOf(values))
    else {
      values.foreach { a =>
        val text = singleText(a.value)
        out.addOne(if (text ne null) text else MultilingualText(Map.empty))
      }
    }

  private def linkmlAnnotations(
      rest: mutable.ArrayBuffer[Annotation],
  ): Map[String, LinkmlAnnotation] =
    if (!config.annotations) Map.empty
    else {
      val byProperty = mutable.HashMap.empty[String, mutable.ArrayBuffer[Node]]
      rest.foreach(a =>
        byProperty.getOrElseUpdate(a.property, mutable.ArrayBuffer.empty).addOne(a.value),
      )
      val properties = byProperty.keysIterator.toArray
      java.util.Arrays.sort(properties, Ordering.String)
      val result = VectorMap.newBuilder[String, LinkmlAnnotation]
      val texts = mutable.ArrayBuffer.empty[String]
      val seen = mutable.HashSet.empty[String]
      properties.foreach { property =>
        names.ensurePrefix(property)
        // The key is written as it is, so it is a CURIE if it can be.
        val key = names.curie(property)
        // Dropping language tags can make two values equal, so remove duplicates.
        texts.clear()
        seen.clear()
        byProperty(property).foreach { v =>
          val text = v match {
            case i: Iri => i.value
            case other => textIn(other)
          }
          if ((text ne null) && seen.add(text)) texts.addOne(text)
        }
        if (texts.nonEmpty) {
          val value =
            if (texts.length == 1) yamlString(texts.head)
            else {
              val sb = new java.lang.StringBuilder
              texts.foreach(t => sb.append("- ").append(yamlString(t).value))
              LinkmlAny(sb.toString)
            }
          result.addOne(
            key -> LinkmlAnnotation(extensionTag = names.uri(property), extensionValue = value),
          )
        }
      }
      result.result()
    }
}

private[owl] object OwlAnnotationReader {

  /** The metaslots of a term, and its other annotations. */
  type Documentation = (metadata: Metadata, annotations: Map[String, LinkmlAnnotation])

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

  /** The text of a literal, or `null` if the node is not a literal. */
  private def textIn(node: Node): String = node match {
    case l: Literal => l.value
    case l: LanguageLiteral => l.value
    case _ => null
  }

  private def yamlString(value: String): LinkmlAny = LinkmlAny.text(value)
}

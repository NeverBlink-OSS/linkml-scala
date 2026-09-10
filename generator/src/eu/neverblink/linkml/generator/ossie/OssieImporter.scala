package eu.neverblink.linkml.generator.ossie

import eu.neverblink.linkml.generator.SchemaImporter
import eu.neverblink.linkml.generator.ossie.expression.{Constraints, Expression, Ref}
import eu.neverblink.linkml.generator.util.JsonOutputFormat
import eu.neverblink.linkml.generator.util.JsonOutputFormat.yaml
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.{LinkmlAny, PlainText, Reference, Uri, UriOrCurie}
import eu.neverblink.linkml.schemaview.Case
import org.virtuslab.yaml.{NodeOps, parseYaml}

import java.io.InputStream

import scala.collection.immutable.VectorMap

/** Reads an Apache Ossie (incubating) ontology and produces a LinkML schema. */
class OssieImporter extends SchemaImporter[OssieImporter.Options] {

  import OssieImporter.*

  override protected def defaultOptions: Options = Options()

  override def importSchema(in: InputStream, options: Options = Options()): SchemaDefinitionImpl =
    Conversion(decode(readUtf8(in)), options).schema

  /** Parse and decode an ontology document. Accepts JSON too, since JSON is YAML. */
  private def decode(input: String): OssieOntology =
    parseYaml(input) match {
      case Right(node) => OssieOntology.codec.decode(node)
      case Left(error) => throw RuntimeException(s"Not a readable Ossie ontology: ${error.msg}")
    }
}

object OssieImporter {

  /** Options for [[OssieImporter]].
    *
    * @param schemaId
    *   The `id` of the schema to produce. By default it is a placeholder built from the ontology's
    *   name.
    * @param outputFormat
    *   Output serialization format to use.
    */
  final case class Options(
      schemaId: Option[String] = None,
      outputFormat: JsonOutputFormat = yaml,
  ) extends SchemaImporter.Options

  /** The LinkML type behind each of Ossie's built-in value types. */
  private val linkmlTypes: Map[String, String] = Map(
    BuiltInConcept.boolean -> "boolean",
    BuiltInConcept.date -> "date",
    BuiltInConcept.dateTime -> "datetime",
    BuiltInConcept.decimal -> "decimal",
    BuiltInConcept.float -> "float",
    BuiltInConcept.integer -> "integer",
    BuiltInConcept.string -> "string",
    BuiltInConcept.any -> "string", // TODO: decide what to do with Any as a type...
  )

  /** The class standing in for Ossie's `Any`, declared only when something actually refers to it.
    */
  private val anyClass = "Any"

  /** Ossie's `identify_by` key has no name of its own, so a compound key one is named after the
    * field.
    */
  private val uniqueKeyName = "identify_by"

  /** One ontology file import. */
  private final class Conversion(ontology: OssieOntology, options: Options) {

    /** Concept name to LinkML element name. */
    private val elementNames: Map[String, String] = {
      val pairs = ontology.ontology.map(c => c.concept -> Case.baseToPascal(Case.base(c.concept)))
      val clashes = pairs.groupBy(_._2).filter(_._2.sizeIs > 1)
      if clashes.nonEmpty then
        throw RuntimeException(
          "Concepts that differ only in naming style cannot both be imported: " +
            clashes.toSeq.sortBy(_._1).map { (element, from) =>
              s"${from.map(_._1).mkString(" and ")} would both become '$element'"
            }.mkString("; "),
        )
      pairs.toMap
    }

    private val (valueTypes, entityTypes) =
      ontology.ontology.partition(_.conceptType == ConceptType.ValueType)

    private val (enumConcepts, typeConcepts) =
      valueTypes.partition(c => permissibleValues(c).isDefined)

    private val typeNames: Set[String] = typeConcepts.map(c => elementNames(c.concept)).toSet

    def schema: SchemaDefinitionImpl = {
      val classes = entityTypes.map(classOf)
      // Any class is only declared when something actually refers to it.
      // TODO: should this be a built-in type/class hybrid instead?
      val any = Option.when(
        classes.exists(_.attributes.values.exists(_.range.contains(Reference(anyClass)))) &&
          !classes.exists(_.name == anyClass),
      )(ClassDefinitionImpl(name = anyClass, classUri = Some(UriOrCurie("linkml:Any"))))

      SchemaDefinitionImpl(
        id = Uri(options.schemaId.getOrElse {
          val slug = Case.base(ontology.name)
          "https://example.org/" + (if slug.isEmpty then "ontology" else slug)
        }),
        name = ontology.name,
        description = ontology.description.map(PlainText.apply),
        prefixes = VectorMap(
          "linkml" -> PrefixImpl("linkml", Uri("https://w3id.org/linkml/")),
        ),
        defaultRange = Some(Reference("string")),
        imports = Seq(UriOrCurie("linkml:types")),
        extensions = aiContext,
        classes = byName(classes ++ any),
        enums = byName(enumConcepts.map(enumOf)),
        types = byName(typeConcepts.map(typeOf)),
      )
    }

    /** The ontology's `ai_context`, as the `extensions` metaslot in LinkML. */
    private def aiContext: Map[String, ExtensionImpl] =
      ontology.aiContext.map { node =>
        val tag = UriOrCurie("ai_context")
        tag.original -> ExtensionImpl(extensionTag = tag, extensionValue = LinkmlAny(node.asYaml))
      }.toMap

    private def classOf(concept: Concept): ClassDefinitionImpl = {
      // The generator lists mixins before `is_a`, so the last entry is the one that was `is_a`.
      val parents = concept.extendsConcepts.map(name => elementNames.getOrElse(name, name))
      val declared = concept.relationships.map(r => r.name -> r).toMap
      val requires = concept.requires.flatMap(Expression.parse)
      val conceptRef = Ref(concept.concept)
      val required = requires.collect {
        case Expression.Member(r, relationship) if r == conceptRef => relationship
      }.toSet

      val identifier = concept.identifyBy match {
        case Seq(only) if declared.get(only).exists(canIdentify) => Some(only)
        case _ => None
      }
      val compoundKey = Option.when(
        identifier.isEmpty && concept.identifyBy.exists(declared.contains),
      )(
        UniqueKeyImpl(
          uniqueKeyName = uniqueKeyName,
          uniqueKeySlots = concept.identifyBy.map(name => Reference(Case.base(name))),
        ),
      )

      val name = elementNames(concept.concept)
      ClassDefinitionImpl(
        name = name,
        alias = Option.when(name != concept.concept)(concept.concept),
        description = concept.description.map(PlainText.apply),
        isA = parents.lastOption.map(Reference.apply),
        mixins = parents.dropRight(1).map(Reference.apply),
        attributes = VectorMap.from(
          concept.relationships.map(r =>
            attributeOf(r, required(r.name), identifier.contains(r.name)),
          ),
        ),
        uniqueKeys = compoundKey.map(uniqueKeyName -> _).toMap,
      )
    }

    /** Whether a relationship can become LinkML's `identifier`, which has to be a single-valued
      * scalar. Others have to go through `unique_keys` instead.
      */
    private def canIdentify(relationship: Relationship): Boolean =
      relationship.multiplicity.contains(Multiplicity.OneToOne) &&
        relationship.roles.headOption.map(_.concept).exists(c =>
          linkmlTypes.contains(c) || typeNames.contains(elementNames.getOrElse(c, c)),
        )

    private def attributeOf(
        relationship: Relationship,
        required: Boolean,
        identifier: Boolean,
    ): (String, SlotDefinitionImpl) = {
      val name = Case.base(relationship.name)
      // We don't support multi-role relationships yet, so we only look at the first one.
      // TODO: consider synthesizing a LinkML class for the relationship and using it as the range.
      val role = relationship.roles.headOption
      val constraints = role
        .map(r => Constraints.from(Ref(r.ref), relationship.requires.flatMap(Expression.parse)))
        .getOrElse(Constraints())
      name -> SlotDefinitionImpl(
        name = name,
        alias = Option.when(name != relationship.name)(relationship.name),
        description = relationship.description.map(PlainText.apply),
        range = role.map(r => Reference(rangeOf(r.concept))),
        multivalued = relationship.multiplicity.isEmpty,
        required = required,
        identifier = identifier,
        minimumValue = constraints.minimumValue,
        maximumValue = constraints.maximumValue,
        pattern = constraints.pattern,
      )
    }

    private def enumOf(concept: Concept): EnumDefinitionImpl =
      EnumDefinitionImpl(
        name = elementNames(concept.concept),
        description = concept.description.map(PlainText.apply),
        permissibleValues = VectorMap.from(
          permissibleValues(concept).getOrElse(Nil).map(v => v -> PermissibleValueImpl(text = v)),
        ),
      )

    private def typeOf(concept: Concept): TypeDefinitionImpl = {
      val constraints =
        Constraints.from(Ref(concept.concept), concept.requires.flatMap(Expression.parse))
      TypeDefinitionImpl(
        name = elementNames(concept.concept),
        description = concept.description.map(PlainText.apply),
        typeof =
          Some(Reference(concept.extendsConcepts.headOption.map(baseOf).getOrElse("string"))),
        minimumValue = constraints.minimumValue,
        maximumValue = constraints.maximumValue,
        pattern = constraints.pattern,
      )
    }

    /** The type a value type is based on. */
    private def baseOf(concept: String): String =
      linkmlTypes.getOrElse(
        concept, {
          val element = elementNames.getOrElse(concept, concept)
          if typeNames.contains(element) then element else "string"
        },
      )

    /** The LinkML range for the concept playing a role. */
    private def rangeOf(concept: String): String =
      if concept == BuiltInConcept.any then anyClass
      else linkmlTypes.getOrElse(concept, elementNames.getOrElse(concept, concept))

    /** The values a value type allows, if it is the enumerated kind. */
    private def permissibleValues(concept: Concept): Option[Seq[String]] =
      concept.requires match {
        case Seq(only) =>
          Expression.parse(only).collect {
            case Expression.InList(r, values) if r == Ref(concept.concept) => values.map(_.value)
          }
        case _ => None
      }
  }

  private def byName[T <: Element](elements: Seq[T]): Map[String, T] =
    VectorMap.from(elements.map(e => e.name -> e))
}

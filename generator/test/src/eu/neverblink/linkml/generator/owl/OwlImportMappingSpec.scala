package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.generator.owl.ClassExpr.*
import eu.neverblink.linkml.generator.owl.config.OwlImportConfigs.classStyle
import eu.neverblink.linkml.generator.owl.config.{
  NameStyle,
  OwlImportConfigImpl,
  OwlImportConfigs,
  SomeValuesFrom as SomeValuesFromStyle,
}
import eu.neverblink.linkml.metamodel.{AnonymousSlotExpressionImpl, SchemaDefinitionImpl}
import eu.neverblink.linkml.rdf.{Iri, LanguageLiteral, Literal, Owl, Rdfs, XmlSchema}
import eu.neverblink.linkml.runtime.{Curie, MultilingualText, PlainText, Reference}
import eu.neverblink.linkml.schemaview.{MapImporter, yamlAs}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

class OwlImportMappingSpec extends AnyWordSpec, Matchers {
  import OwlImportMappingSpec.*

  "classes" should {
    "take the parent from the ontology's own namespace as is_a, and the others as mixins" in {
      val s = schema(
        cls("A"),
        cls("B"),
        Declaration(EntityKind.Class, other + "Z"),
        SubClassOf(c("A"), Named(other + "Z")),
        SubClassOf(c("A"), c("B")),
      )
      s.classes("A").isA shouldBe Some(Reference("B"))
      s.classes("A").mixins shouldBe Seq(Reference("Z"))
      s.classes("Z").classUri shouldBe Some(Curie("other:Z"))
    }

    "drop owl:Thing as a parent" in {
      schema(cls("A"), SubClassOf(c("A"), Thing)).classes("A").isA shouldBe None
    }

    "break a subclass cycle, which LinkML cannot have" in {
      val result =
        importOf(cls("A"), cls("B"), SubClassOf(c("A"), c("B")), SubClassOf(c("B"), c("A")))
      result.schema.classes("B").isA shouldBe None
      result.warnings.exists(_.startsWith("Subclass cycle")) shouldBe true
    }

    "become abstract when they are covered by their children" in {
      val s = schema(
        cls("A"),
        cls("B"),
        cls("C"),
        SubClassOf(c("B"), c("A")),
        SubClassOf(c("C"), c("A")),
        SubClassOf(c("A"), UnionOf(Seq(c("B"), c("C")))),
      )
      s.classes("A").`abstract` shouldBe true
    }

    "keep disjointness" in {
      schema(cls("A"), cls("B"), DisjointClasses(Seq(c("A"), c("B")))).classes(
        "A",
      ).disjointWith shouldBe
        Seq(Reference("B"))
    }

    "turn an equivalent named class into an exact mapping" in {
      schema(cls("A"), cls("B"), EquivalentClasses(Seq(c("A"), c("B"))))
        .classes("A").exactMappings shouldBe Seq(Curie("ex:B"))
    }

    "keep what LinkML cannot say in the notes, and report it" in {
      val result = importOf(cls("A"), cls("B"), SubClassOf(c("A"), ComplementOf(c("B"))))
      result.schema.classes("A").notes shouldBe Seq(PlainText("OWL: not ex:B"))
      result.warnings.exists(_.contains("not ex:B")) shouldBe true
    }
  }

  "properties" should {
    "become multivalued slots, unless they are functional" in {
      val s = schema(
        obj("p"),
        obj("q"),
        Characteristic(ns + "q", PropertyCharacteristic.Functional),
      )
      s.slotDefinitions("p").multivalued shouldBe true
      s.slotDefinitions("q").multivalued shouldBe false
    }

    "be snake_cased, with the IRI kept in slot_uri" in {
      val s = schema(obj("hasPart"))
      s.slotDefinitions("has_part").slotUri shouldBe Some(Curie("ex:hasPart"))
    }

    "get their range, or Any when they have none" in {
      val s = schema(cls("A"), obj("p"), obj("q"), Range(ns + "p", c("A")))
      s.slotDefinitions("p").range shouldBe Some(Reference("A"))
      s.slotDefinitions("q").range shouldBe Some(Reference("Any"))
      s.imports.map(_.original) should contain("linkml:extended_types")
    }

    "say they are data properties when the range does not" in {
      val s = schema(data("d"))
      s.slotDefinitions("d").implements.map(_.original) shouldBe Seq("owl:DatatypeProperty")
    }

    "turn a union range into any_of" in {
      val s = schema(cls("A"), cls("B"), obj("p"), Range(ns + "p", UnionOf(Seq(c("A"), c("B")))))
      s.slotDefinitions("p").anyOf shouldBe Seq(
        AnonymousSlotExpressionImpl(range = Some(Reference("A"))),
        AnonymousSlotExpressionImpl(range = Some(Reference("B"))),
      )
    }

    "attach to the class of their domain, or each class of a union domain" in {
      val s = schema(
        cls("A"),
        cls("B"),
        obj("p"),
        obj("q"),
        Domain(ns + "p", c("A")),
        Domain(ns + "q", UnionOf(Seq(c("A"), c("B")))),
      )
      s.slotDefinitions("p").domain shouldBe Some(Reference("A"))
      s.slotDefinitions("q").domainOf shouldBe Seq(Reference("A"), Reference("B"))
      s.classes("A").slots shouldBe Seq(Reference("p"), Reference("q"))
      s.classes("B").slots shouldBe Seq(Reference("q"))
    }

    "keep their characteristics, inverses and parents" in {
      val s = schema(
        obj("p"),
        obj("q"),
        obj("r"),
        Characteristic(ns + "p", PropertyCharacteristic.Transitive),
        InverseProperties(ns + "p", ns + "q"),
        SubPropertyOf(ns + "r", ns + "p"),
      )
      s.slotDefinitions("p").transitive shouldBe true
      s.slotDefinitions("p").inverse shouldBe Some(Reference("q"))
      s.slotDefinitions("q").inverse shouldBe Some(Reference("p"))
      s.slotDefinitions("r").isA shouldBe Some(Reference("p"))
    }

    "keep a parent from outside the ontology, as a slot of the same kind" in {
      val result = importOf(
        obj("p"),
        data("d"),
        SubPropertyOf(ns + "p", other + "related"),
        SubPropertyOf(ns + "d", other + "value"),
      )
      val s = result.schema
      s.slotDefinitions("p").isA shouldBe Some(Reference("related"))
      s.slotDefinitions("related").slotUri shouldBe Some(Curie("other:related"))
      s.slotDefinitions("d").isA shouldBe Some(Reference("value"))
      s.slotDefinitions("value").implements.map(_.original) shouldBe Seq("owl:DatatypeProperty")
      result.warnings shouldBe empty
    }

    "not make a slot of an outside parent that is used for annotations" in {
      val result = importOf(
        data("d"),
        SubPropertyOf(ns + "d", other + "note"),
        AnnotationAssertion(ns + "d", Annotation(other + "note", Literal("A note."))),
      )
      result.schema.slotDefinitions.keySet should not contain "note"
      result.warnings.exists(_.startsWith("Subproperty of an annotation")) shouldBe true
    }
  }

  "restrictions" should {
    val base = Seq(
      cls("A"),
      cls("B"),
      cls("C"),
      SubClassOf(c("C"), c("B")),
      obj("p"),
      Range(ns + "p", c("B")),
    )

    def usage(restriction: ClassExpr, config: OwlImportConfigImpl = OwlImportConfigImpl()) =
      schema(config, base :+ SubClassOf(c("A"), restriction)*).classes("A")

    "attach the slot, and narrow its range with allValuesFrom" in {
      usage(AllValuesFrom(PropertyRef(ns + "p"), c("B"))).slots shouldBe Seq(Reference("p"))
      usage(AllValuesFrom(PropertyRef(ns + "p"), c("C"))).slotUsage("p").range shouldBe Some(
        Reference("C"),
      )
    }

    "make the slot required with someValuesFrom" in {
      val a = usage(SomeValuesFrom(PropertyRef(ns + "p"), c("B")))
      a.slotUsage("p").required shouldBe true
      a.slotUsage("p").hasMember shouldBe None
    }

    "say which kind of value there has to be when someValuesFrom narrows the range" in {
      val p = usage(SomeValuesFrom(PropertyRef(ns + "p"), c("C"))).slotUsage("p")
      p.required shouldBe true
      p.hasMember shouldBe Some(AnonymousSlotExpressionImpl(range = Some(Reference("C"))))
    }

    "narrow the range instead, when asked to" in {
      val p = usage(
        SomeValuesFrom(PropertyRef(ns + "p"), c("C")),
        OwlImportConfigImpl(someValuesFrom = Some(SomeValuesFromStyle.Range)),
      ).slotUsage("p")
      p.required shouldBe true
      p.range shouldBe Some(Reference("C"))
    }

    "turn cardinalities into required and the cardinality metaslots" in {
      usage(Cardinality(Bound.Min, 1, PropertyRef(ns + "p"))).slotUsage("p").required shouldBe true
      usage(Cardinality(Bound.Min, 2, PropertyRef(ns + "p"))).slotUsage(
        "p",
      ).minimumCardinality shouldBe Some(2)
      usage(Cardinality(Bound.Max, 3, PropertyRef(ns + "p"))).slotUsage(
        "p",
      ).maximumCardinality shouldBe Some(3)
      usage(Cardinality(Bound.Exact, 2, PropertyRef(ns + "p"))).slotUsage(
        "p",
      ).exactCardinality shouldBe Some(2)
      val exactlyOne = usage(Cardinality(Bound.Exact, 1, PropertyRef(ns + "p"))).slotUsage("p")
      exactlyOne.required shouldBe true
      exactlyOne.maximumCardinality shouldBe Some(1)
    }

    "turn hasValue into equals_string" in {
      usage(HasValue(PropertyRef(ns + "p"), Iri(ns + "x"))).slotUsage(
        "p",
      ).equalsString shouldBe Some("ex:x")
    }
  }

  "enums" should {
    "come from classes defined by owl:oneOf, with the text from skos:notation or the local name" in {
      val e = schema(
        cls("Status"),
        EquivalentClasses(Seq(c("Status"), OneOf(Seq(ns + "on", ns + "off")))),
        AnnotationAssertion(ns + "on", Annotation.text(notation, "ON")),
        AnnotationAssertion(ns + "on", Annotation.text(Rdfs.label.value, "Switched on")),
      ).enums("Status")
      e.permissibleValues.keys.toSeq shouldBe Seq("ON", "off")
      e.permissibleValues("ON").meaning shouldBe Some(Curie("ex:on"))
      e.permissibleValues("ON").title shouldBe Some(PlainText("Switched on"))
    }

    "come from classes that only have individuals, when a property ranges over them" in {
      val axioms = Seq(
        cls("State"),
        ClassAssertion(c("State"), ns + "Open"),
        ClassAssertion(c("State"), ns + "Closed"),
        obj("state"),
        Range(ns + "state", c("State")),
      )
      schema(axioms*).enums("State").permissibleValues.keySet shouldBe Set("Open", "Closed")
      schema(OwlImportConfigImpl(enumsFromIndividuals = false), axioms*).classes.contains(
        "State",
      ) shouldBe
        true
    }

    "come from classes as Schema.org has them: with a parent, and ranged over by rangeIncludes" in {
      val rangeIncludes = "https://schema.org/rangeIncludes"
      val result = importOf(
        cls("Enumeration"),
        cls("StatusEnumeration"),
        cls("LegalForceStatus"),
        cls("NonprofitType"),
        cls("USNonprofitType"),
        SubClassOf(c("StatusEnumeration"), c("Enumeration")),
        SubClassOf(c("LegalForceStatus"), c("StatusEnumeration")),
        SubClassOf(c("USNonprofitType"), c("NonprofitType")),
        EquivalentClasses(Seq(c("LegalForceStatus"), Named(other + "LegalForce"))),
        ClassAssertion(c("LegalForceStatus"), ns + "InForce"),
        ClassAssertion(c("LegalForceStatus"), ns + "NotInForce"),
        ClassAssertion(c("USNonprofitType"), ns + "Nonprofit501c3"),
        obj("legalForce"),
        obj("nonprofitStatus"),
        AnnotationAssertion(
          ns + "legalForce",
          Annotation(rangeIncludes, Iri(ns + "LegalForceStatus")),
        ),
        // The subclass of a range has its values too.
        AnnotationAssertion(
          ns + "nonprofitStatus",
          Annotation(rangeIncludes, Iri(ns + "NonprofitType")),
        ),
      )
      val e = result.schema.enums("LegalForceStatus")
      e.permissibleValues.keySet shouldBe Set("InForce", "NotInForce")
      e.exactMappings.map(_.original) shouldBe Seq("other:LegalForce")
      result.schema.enums("USNonprofitType").permissibleValues.keySet shouldBe Set("Nonprofit501c3")
      e.isA.map(_.value) shouldBe Some("StatusEnumeration")
      result.schema.classes.keySet should contain allOf ("StatusEnumeration", "NonprofitType")
    }
  }

  "datatypes" should {
    "map XSD datatypes to LinkML types, adding types for the ones LinkML has not" in {
      val s = schema(
        data("a"),
        data("b"),
        Range(ns + "a", DataRange.Datatype(XmlSchema.string.value)),
        Range(ns + "b", DataRange.Datatype(XmlSchema.get("int").value)),
      )
      s.slotDefinitions("a").range shouldBe Some(Reference("string"))
      s.slotDefinitions("b").range shouldBe Some(Reference("int"))
      s.types("int").typeof shouldBe Some(Reference("integer"))
    }

    "use the LinkML type the config gives a datatype" in {
      val config = OwlImportConfigs.parse("datatypes:\n  xsd:gYear: integer\n")
      val s = schema(
        config,
        data("year"),
        Range(ns + "year", DataRange.Datatype(XmlSchema.get("gYear").value)),
      )
      s.slotDefinitions("year").range shouldBe Some(Reference("integer"))
      s.types shouldBe empty
    }

    "turn a datatype restriction into the slot's constraints" in {
      val s = schema(
        data("age"),
        Range(
          ns + "age",
          DataRange.Restriction(
            XmlSchema.integer.value,
            Seq(XmlSchema.minInclusive.value -> Literal("0", XmlSchema.integer)),
          ),
        ),
      )
      s.slotDefinitions("age").range shouldBe Some(Reference("integer"))
      s.slotDefinitions("age").minimumValue.map(_.value) shouldBe Some("0")
    }
  }

  "documentation" should {
    "fill the metaslots, leaving out a label that is just the name" in {
      val a = schema(
        cls("A"),
        AnnotationAssertion(ns + "A", Annotation.text(Rdfs.label.value, "A")),
        AnnotationAssertion(ns + "A", Annotation.text(Rdfs.comment.value, "First.")),
        AnnotationAssertion(ns + "A", Annotation.text(Rdfs.comment.value, "Second.")),
        AnnotationAssertion(ns + "A", Annotation(Rdfs.seeAlso.value, Iri(ns + "B"))),
        AnnotationAssertion(
          ns + "A",
          Annotation(Owl.deprecated.value, Literal("true", XmlSchema.boolean)),
        ),
      ).classes("A")
      a.title shouldBe None
      a.description shouldBe Some(PlainText("First."))
      a.comments shouldBe Seq(PlainText("Second."))
      a.seeAlso shouldBe Seq(Curie("ex:B"))
      a.deprecated shouldBe Some(PlainText("true"))
    }

    "keep titles in several languages" in {
      schema(
        cls("A"),
        AnnotationAssertion(ns + "A", Annotation(Rdfs.label.value, LanguageLiteral("Thing", "en"))),
        AnnotationAssertion(ns + "A", Annotation(Rdfs.label.value, LanguageLiteral("Ding", "de"))),
      ).classes("A").title shouldBe Some(MultilingualText(Map("en" -> "Thing", "de" -> "Ding")))
    }

    "keep the languages of comments" in {
      def comment(text: String, lang: String) =
        AnnotationAssertion(ns + "A", Annotation(Rdfs.comment.value, LanguageLiteral(text, lang)))
      // The first comment becomes the description.
      val a = schema(
        cls("A"),
        comment("Description.", "en"),
        comment("Note.", "en"),
        comment("Notiz.", "de"),
      ).classes("A")
      a.comments shouldBe Seq(MultilingualText(Map("en" -> "Note.", "de" -> "Notiz.")))
      // Two notes in one language can't be paired up as translations, so each stays separate.
      schema(
        cls("A"),
        AnnotationAssertion(ns + "A", Annotation(skosNote, LanguageLiteral("One.", "en"))),
        AnnotationAssertion(ns + "A", Annotation(skosNote, LanguageLiteral("Two.", "en"))),
        AnnotationAssertion(ns + "A", Annotation(skosNote, LanguageLiteral("Eins.", "de"))),
      ).classes("A").comments shouldBe Seq(
        MultilingualText(Map("en" -> "One.")),
        MultilingualText(Map("en" -> "Two.")),
        MultilingualText(Map("de" -> "Eins.")),
      )
    }

    "keep a label that repeats the name as one language of the title" in {
      def label(text: String, lang: String) =
        AnnotationAssertion(ns + "A", Annotation(Rdfs.label.value, LanguageLiteral(text, lang)))
      schema(cls("A"), label("A", "en"), label("Ah", "de")).classes("A").title shouldBe
        Some(MultilingualText(Map("en" -> "A", "de" -> "Ah")))
      // The title keeps both languages even when English is the schema's language.
      val comments = (0 until 10).map(i =>
        AnnotationAssertion(
          ns + "B",
          Annotation(Rdfs.comment.value, LanguageLiteral(s"Comment $i.", "en")),
        ),
      )
      val s = schema(Seq(cls("A"), cls("B"), label("A", "en"), label("Ah", "de")) ++ comments*)
      s.inLanguage shouldBe Some("en")
      s.classes("A").title shouldBe Some(MultilingualText(Map("en" -> "A", "de" -> "Ah")))
      // A label that repeats the name is dropped when another label has the same language.
      schema(cls("A"), label("A", "en"), label("Alpha", "en")).classes("A").title shouldBe
        Some(PlainText("Alpha"))
    }

    "make the language of all text the schema's in_language" in {
      val s = schema(
        cls("A"),
        AnnotationAssertion(
          ns + "A",
          Annotation(Rdfs.comment.value, LanguageLiteral("A thing.", "en")),
        ),
      )
      s.inLanguage shouldBe Some("en")
      s.classes("A").description shouldBe Some(PlainText("A thing."))
    }

    "find the language from text written for people only" in {
      val code =
        (0 until 5).map(i => AnnotationAssertion(ns + "A", Annotation.text(ns + "code", s"x $i")))
      val s = schema(
        Seq(
          cls("A"),
          AnnotationAssertion(
            ns + "A",
            Annotation(Rdfs.label.value, LanguageLiteral("Thing", "en")),
          ),
        ) ++ code*,
      )
      s.inLanguage shouldBe Some("en")
      s.classes("A").title shouldBe Some(PlainText("Thing"))
    }

    "keep the language of a text when the schema has none" in {
      // Untagged comments mean the schema gets no language.
      val untagged = (0 until 3).map(i =>
        AnnotationAssertion(ns + "B", Annotation.text(Rdfs.comment.value, s"Comment $i.")),
      )
      def label(text: String) =
        AnnotationAssertion(ns + "A", Annotation(Rdfs.label.value, LanguageLiteral(text, "de")))
      val s = schema(Seq(cls("A"), cls("B"), label("Ding")) ++ untagged*)
      s.inLanguage shouldBe None
      s.classes("A").title shouldBe Some(MultilingualText(Map("de" -> "Ding")))
      // This holds even when the label repeats the name.
      schema(Seq(cls("A"), cls("B"), label("A")) ++ untagged*).classes("A").title shouldBe
        Some(MultilingualText(Map("de" -> "A")))
      // But not when skos:prefLabel gives the title.
      val skosPrefLabel = "http://www.w3.org/2004/02/skos/core#prefLabel"
      val prefLabels = Seq("en" -> "A", "fr" -> "Un A").map((l, t) =>
        AnnotationAssertion(ns + "A", Annotation(skosPrefLabel, LanguageLiteral(t, l))),
      )
      schema(Seq(cls("A"), cls("B"), label("A")) ++ untagged ++ prefLabels*).classes("A")
        .title shouldBe Some(MultilingualText(Map("en" -> "A", "fr" -> "Un A")))
    }

    "keep other annotations as LinkML annotations" in {
      schema(cls("A"), AnnotationAssertion(ns + "A", Annotation.text(ns + "note", "hi")))
        .classes("A").annotations("ex:note").extensionValue.yamlAs[String] shouldBe Right("hi")
    }

    "read the description from the properties the config names" in {
      val config = OwlImportConfigs.parse("metadata:\n  description: [ex:definition]\n")
      schema(
        config,
        cls("A"),
        AnnotationAssertion(ns + "A", Annotation.text(ns + "definition", "Defined.")),
      )
        .classes("A").description shouldBe Some(PlainText("Defined."))
    }
  }

  "names" should {
    "tell apart terms with the same local name by their prefix" in {
      val s = schema(cls("Agent"), Declaration(EntityKind.Class, other + "Agent"))
      s.classes("Agent").classUri shouldBe None
      s.classes("OtherAgent").classUri shouldBe Some(Curie("other:Agent"))
    }

    "not take the names of LinkML's types" in {
      schema(data("date")).slotDefinitions.keySet should contain("date_2")
    }

    "follow renames and naming styles from the config" in {
      val config = OwlImportConfigs.parse(
        """renames:
          |  ex:A: Renamed
          |naming:
          |  slots: keep
          |""".stripMargin,
      )
      val s = schema(config, cls("A"), obj("hasPart"))
      s.classes.keySet should contain("Renamed")
      s.slotDefinitions.keySet should contain("hasPart")
    }
  }

  "imports" should {
    val imported =
      """id: https://other.example.com/schema
        |name: other_schema
        |prefixes:
        |  other: https://other.example.com/
        |default_prefix: other
        |imports: [linkml:types]
        |classes:
        |  Animal:
        |  Agent:
        |slots:
        |  related:
        |    range: Agent
        |""".stripMargin
    val axioms = Seq(
      cls("Dog"),
      obj("owner"),
      SubClassOf(c("Dog"), Named(other + "Animal")),
      Range(ns + "owner", Named(other + "Agent")),
      SubPropertyOf(ns + "owner", other + "related"),
    )
    val config = OwlImportConfigs.parse("imports:\n  https://other.example.com/onto: other.yaml\n")
    def importWith(schemas: (String, String)*)(extra: Axiom*): OwlImporter.Result =
      OwlImporter().importOntology(
        Ontology(
          prefixes = prefixes,
          imports = Seq("https://other.example.com/onto"),
          axioms = axioms ++ extra,
        ),
        OwlImporter.Options(config, schemas = MapImporter(schemas*)),
        Nil,
      )

    "use the terms of a mapped LinkML schema by name, and not copy them in" in {
      val result = importWith("other.yaml" -> imported)()
      val s = result.schema
      s.imports.map(_.original) shouldBe Seq("linkml:types", "other.yaml")
      s.classes.keySet shouldBe Set("Dog")
      s.slotDefinitions.keySet shouldBe Set("owner")
      s.classes("Dog").isA shouldBe Some(Reference("Animal"))
      s.slotDefinitions("owner").range shouldBe Some(Reference("Agent"))
      s.slotDefinitions("owner").isA shouldBe Some(Reference("related"))
      result.warnings shouldBe empty
    }

    "report what the ontology says about a term of the imported schema" in {
      val result =
        importWith("other.yaml" -> imported)(SubClassOf(Named(other + "Animal"), c("Dog")))
      result.warnings.exists(_.startsWith("Statement about a term of an imported schema")) shouldBe
        true
    }

    "copy the terms in when the mapped schema cannot be read" in {
      val result = importWith()()
      result.schema.classes.keySet shouldBe Set("Dog", "Animal", "Agent")
      result.schema.slotDefinitions.keySet shouldBe Set("owner", "related")
      result.warnings.exists(_.startsWith("Imported LinkML schema that could not be read")) shouldBe
        true
    }

    "leave out an import that is not mapped" in {
      val result = OwlImporter().importOntology(
        Ontology(prefixes = prefixes, imports = Seq(other + "onto"), axioms = axioms),
      )
      result.schema.imports.map(_.original).filterNot(_.startsWith("linkml:")) shouldBe empty
      result.warnings.exists(_.startsWith("Import that is not mapped")) shouldBe true
    }
  }

  "the schema" should {
    "take its id from the ontology, and make up a prefix for the namespace of its terms" in {
      val s = OwlImporter().importOntology(
        Ontology(iri = Some("https://example.org/onto"), axioms = Seq(cls("A"))),
      ).schema
      s.id.original shouldBe "https://example.org/onto"
      s.defaultPrefix shouldBe Some("example")
      s.prefixes("example").prefixReference.original shouldBe ns
    }

    "use the importer's config, read from YAML" in {
      val config = OwlImportConfigs.parse(
        """schema_id: https://example.org/mine
          |naming:
          |  classes: pascal
          |""".stripMargin,
      )
      config.schemaId shouldBe Some("https://example.org/mine")
      config.classStyle shouldBe NameStyle.Pascal
    }

    "be read from N-Triples" in {
      val nt =
        s"""<${ns}A> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Class> .
           |<${ns}A> <http://www.w3.org/2000/01/rdf-schema#comment> "A thing." .
           |""".stripMargin
      val s = OwlImporter().importSchema(ByteArrayInputStream(nt.getBytes(UTF_8)))
      s.classes("A").description shouldBe Some(PlainText("A thing."))
    }
  }
}

object OwlImportMappingSpec {
  val ns = "https://example.org/"
  val other = "https://other.example.com/"
  private val notation = "http://www.w3.org/2004/02/skos/core#notation"

  def c(local: String): Named = Named(ns + local)
  private val skosNote = "http://www.w3.org/2004/02/skos/core#note"

  def cls(local: String): Axiom = Declaration(EntityKind.Class, ns + local)
  def obj(local: String): Axiom = Declaration(EntityKind.ObjectProperty, ns + local)
  def data(local: String): Axiom = Declaration(EntityKind.DataProperty, ns + local)

  private val prefixes = Seq("ex" -> ns, "other" -> other)

  def importOf(axioms: Axiom*): OwlImporter.Result =
    OwlImporter().importOntology(Ontology(prefixes = prefixes, axioms = axioms))

  def schema(axioms: Axiom*): SchemaDefinitionImpl = importOf(axioms*).schema

  def schema(config: OwlImportConfigImpl, axioms: Axiom*): SchemaDefinitionImpl =
    OwlImporter().importOntology(Ontology(prefixes = prefixes, axioms = axioms), config).schema
}

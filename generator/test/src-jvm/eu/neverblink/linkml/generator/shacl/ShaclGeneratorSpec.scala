package eu.neverblink.linkml.generator.shacl

import eu.neverblink.linkml.generator.rdf.{CollectingRdfSink, RdfUtils}
import eu.neverblink.linkml.schemaview.SchemaIssues
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.rio.{RDFFormat, Rio}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.StringReader

class ShaclGeneratorSpec extends AnyWordSpec, Matchers {
  import ShaclGeneratorSpec.skipModels
  given vf: ValueFactory = SimpleValueFactory.getInstance()

  def ttlIsomorphic(actual: String, expected: String): Unit = {
    val ac = Rio.parse(StringReader(actual), RDFFormat.TURTLE)
    val ex = Rio.parse(StringReader(expected), RDFFormat.TURTLE)
    withClue(s"$actual\n\nis not isomorphic to the expected\n\n$expected") {
      Models.isomorphic(ac, ex) shouldBe true
    }
  }

  "ShaclGenerator" should {
    def loadWithImports(schemaYaml: String): SchemaView =
      SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(schemaYaml))

    // Shared part of the schema
    val schemaShared =
      """id: https://neverblink.eu/linkml/shacl/test/
        |name: test
        |imports:
        |  - linkml:types"""

    "classes with basic types" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    tree_root: true
           |    slots:
           |    - some_slot
           |    - some_other_slot
           |    - some_yet_another_slot
           |slots:
           |  some_slot:
           |    range: string
           |  some_other_slot:
           |    range: integer
           |  some_yet_another_slot:
           |    range: boolean
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype xsd:string;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 0;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_slot>
          |    ], [
          |      sh:datatype xsd:integer;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 1;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_other_slot>
          |    ], [
          |      sh:datatype xsd:boolean;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 2;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_yet_another_slot>
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeClass> .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "generate counts from the explicit cardinality metaslots" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |    - exact_slot
           |    - bounded_slot
           |    - overridden_slot
           |slots:
           |  exact_slot:
           |    range: string
           |    multivalued: true
           |    exact_cardinality: 3
           |  bounded_slot:
           |    range: string
           |    multivalued: true
           |    minimum_cardinality: 1
           |    maximum_cardinality: 5
           |  overridden_slot:
           |    range: string
           |    required: true
           |    multivalued: false
           |    minimum_cardinality: 2
           |    maximum_cardinality: 4
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype xsd:string;
          |      sh:maxCount 3;
          |      sh:minCount 3;
          |      sh:nodeKind sh:Literal;
          |      sh:order 0;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/exact_slot>
          |    ], [
          |      sh:datatype xsd:string;
          |      sh:maxCount 5;
          |      sh:minCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 1;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/bounded_slot>
          |    ], [
          |      sh:datatype xsd:string;
          |      sh:maxCount 4;
          |      sh:minCount 2;
          |      sh:nodeKind sh:Literal;
          |      sh:order 2;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/overridden_slot>
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeClass> .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "generate sh:pattern and inclusive bounds from slots" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |    - int_slot
           |    - string_slot
           |slots:
           |  int_slot:
           |    range: integer
           |    minimum_value: -1
           |    maximum_value: 10
           |  string_slot:
           |    range: string
           |    pattern: "^[0-9]{3}-[0-9]{4}$$"
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype xsd:integer;
          |      sh:maxCount 1;
          |      sh:maxInclusive 10;
          |      sh:minInclusive -1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 0;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/int_slot>
          |    ], [
          |      sh:datatype xsd:string;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 1;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/string_slot>;
          |      sh:pattern "^[0-9]{3}-[0-9]{4}$"
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeClass> .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "take sh:pattern and inclusive bounds from the range type" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |    - int_slot
           |    - string_slot
           |slots:
           |  int_slot:
           |    range: small_int
           |  string_slot:
           |    range: patterned_string
           |types:
           |  small_int:
           |    base: int
           |    minimum_value: -1
           |    maximum_value: 10
           |  patterned_string:
           |    base: str
           |    pattern: "^[0-9]{3}-[0-9]{4}$$"
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      // The bounds are tagged with the XSD type the base maps to, not the synthetic URI of the
      // custom type, so that a validator can actually compare them.
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype <https://neverblink.eu/linkml/shacl/test/small_int>;
          |      sh:maxCount 1;
          |      sh:maxInclusive 10;
          |      sh:minInclusive -1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 0;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/int_slot>
          |    ], [
          |      sh:datatype <https://neverblink.eu/linkml/shacl/test/patterned_string>;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 1;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/string_slot>;
          |      sh:pattern "^[0-9]{3}-[0-9]{4}$"
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeClass> .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "prefer the slot's own constraints over the range type's" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |    - int_slot
           |slots:
           |  int_slot:
           |    range: small_int
           |    minimum_value: 5
           |types:
           |  small_int:
           |    base: int
           |    minimum_value: -1
           |    maximum_value: 10
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      turtle should include("sh:minInclusive 5")
      turtle should not include "sh:minInclusive -1"
      // the type still supplies the bound the slot doesn't override
      turtle should include("sh:maxInclusive 10")
    }

    "not emit bounds for ranges that have no ordering" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |    - uri_slot
           |slots:
           |  uri_slot:
           |    range: uriorcurie
           |    minimum_value: 1
           |    maximum_value: 10
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      turtle should not include "sh:minInclusive"
      turtle should not include "sh:maxInclusive"
    }

    "generate sh:name, sh:group and rank-based sh:order" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |    - some_slot
           |    - some_other_slot
           |slots:
           |  contact_info:
           |    title: Contact information
           |    description: Slots describing how to reach someone.
           |    rank: 5
           |  some_slot:
           |    range: string
           |    title: Some Slot
           |    slot_group: contact_info
           |    rank: 2
           |  some_other_slot:
           |    range: integer
           |    rank: 1
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype xsd:string;
          |      sh:group <https://neverblink.eu/linkml/shacl/test/contact_info>;
          |      sh:maxCount 1;
          |      sh:name "Some Slot";
          |      sh:nodeKind sh:Literal;
          |      sh:order 2;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_slot>
          |    ], [
          |      sh:datatype xsd:integer;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 1;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_other_slot>
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeClass> .
          |
          |<https://neverblink.eu/linkml/shacl/test/contact_info> a sh:PropertyGroup;
          |  rdfs:label "Contact information";
          |  rdfs:comment "Slots describing how to reach someone.";
          |  sh:order 5 .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "order unranked slots after ranked ones" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |    - unranked_slot
           |    - ranked_slot
           |    - another_unranked_slot
           |slots:
           |  unranked_slot:
           |    range: string
           |  ranked_slot:
           |    range: string
           |    rank: 10
           |  another_unranked_slot:
           |    range: string
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype xsd:string;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 11;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/unranked_slot>
          |    ], [
          |      sh:datatype xsd:string;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 10;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/ranked_slot>
          |    ], [
          |      sh:datatype xsd:string;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 12;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/another_unranked_slot>
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeClass> .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "declare a shared sh:PropertyGroup only once" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |    - some_slot
           |  SomeOtherClass:
           |    slots:
           |    - some_slot
           |slots:
           |  contact_info: {}
           |  some_slot:
           |    range: string
           |    slot_group: contact_info
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val sink = new CollectingRdfSink
      ShaclGenerator(using schemaView).generate(sink)
      withClue("duplicate triples in the output:") {
        sink.triples.diff(sink.triples.distinct) shouldBe empty
      }
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      "sh:PropertyGroup".r.findAllMatchIn(turtle).size shouldBe 1
      "sh:group ".r.findAllMatchIn(turtle).size shouldBe 2
      // falls back to the slot name when the group slot has no title
      turtle should include("rdfs:label \"contact_info\"")
    }

    "enforce open shapes" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    tree_root: true
           |    slots:
           |    - some_slot
           |slots:
           |  some_slot:
           |    range: string
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle =
        RdfUtils.toTurtle(
          ShaclGenerator(using schemaView).generate(_, ShaclGenerator.Options(open = true)),
        )
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape;
          |  sh:closed false;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype xsd:string;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 0;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_slot>
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeClass> .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "all classes in $defs" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeOtherClass:
           |    slots:
           |    - some_slot
           |  SomeClass:
           |    slots:
           |    - some_slot
           |    - some_other_slot
           |slots:
           |  some_slot:
           |    range: double
           |  some_other_slot:
           |    range: float
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeOtherClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype xsd:double;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 0;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_slot>
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeOtherClass> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype xsd:double;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 0;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_slot>
          |    ], [
          |      sh:datatype xsd:float;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 1;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_other_slot>
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeClass> .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "reference other classes" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeOtherClass:
           |    attributes:
           |      id:
           |        identifier: true
           |  SomeClass:
           |    slots:
           |    - some_slot
           |slots:
           |  some_slot:
           |    range: SomeOtherClass
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeOtherClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type <https://neverblink.eu/linkml/shacl/test/id>);
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeOtherClass> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:class <https://neverblink.eu/linkml/shacl/test/SomeOtherClass>;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:BlankNodeOrIRI;
          |      sh:order 0;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_slot>
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeClass> .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "handle required references" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeOtherClass:
           |    attributes:
           |      id:
           |        identifier: true
           |  SomeClass:
           |    slots:
           |    - some_slot
           |    - some_other_slot
           |slots:
           |  some_slot:
           |    range: SomeOtherClass
           |    required: true
           |  some_other_slot:
           |    range: decimal
           |    required: true
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeOtherClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type <https://neverblink.eu/linkml/shacl/test/id>);
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeOtherClass> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:class <https://neverblink.eu/linkml/shacl/test/SomeOtherClass>;
          |      sh:maxCount 1;
          |      sh:minCount 1;
          |      sh:nodeKind sh:BlankNodeOrIRI;
          |      sh:order 0;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_slot>
          |    ], [
          |      sh:datatype xsd:decimal;
          |      sh:maxCount 1;
          |      sh:minCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 1;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_other_slot>
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeClass> .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "work for recursive ADT" in {
      val input =
        s"""$schemaShared
           |classes:
           |  Node:
           |    tree_root: true
           |    attributes:
           |      name:
           |        key: true
           |        range: time
           |      children:
           |        # SimpleDict form = { name1: Node1, name2: Node2 }
           |        range: Node
           |        multivalued: true
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/Node> a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype xsd:time;
          |      sh:maxCount 1;
          |      sh:minCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 0;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/name>
          |    ], [
          |      sh:class <https://neverblink.eu/linkml/shacl/test/Node>;
          |      sh:nodeKind sh:BlankNodeOrIRI;
          |      sh:order 1;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/children>
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/Node> .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "works for abstract classes" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    abstract: true
           |    slots:
           |    - some_slot
           |    - some_other_slot
           |slots:
           |  some_slot:
           |    range: date
           |  some_other_slot:
           |    range: time
           |    required: true
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/shacl/test/SomeClass> a sh:NodeShape;
          |  sh:closed false;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype xsd:date;
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 0;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_slot>
          |    ], [
          |      sh:datatype xsd:time;
          |      sh:maxCount 1;
          |      sh:minCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 1;
          |      sh:path <https://neverblink.eu/linkml/shacl/test/some_other_slot>
          |    ];
          |  sh:targetClass <https://neverblink.eu/linkml/shacl/test/SomeClass> .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "works for enums" in {
      val input =
        """id: https://neverblink.eu/example/model/IncidentReport
           |name: IncidentReport
           |description: Data model for a structured incident report from the shop floor.
           |imports:
           |  - linkml:types
           |prefixes:
           |  linkml: https://w3id.org/linkml/
           |  schema: http://schema.org/
           |  nb: https://neverblink.eu/example/
           |  brick: https://brickschema.org/schema/Brick#
           |default_prefix: nb
           |emit_prefixes:
           |  - brick
           |classes:
           |  IncidentReport:
           |    class_uri: nb:IncidentReport
           |    description: A structured incident report from the shop floor.
           |    slots:
           |      - time
           |      - machine
           |      - incidentType
           |  Machine:
           |    class_uri: brick:Equipment
           |enums:
           |  IncidentType:
           |    permissible_values:
           |      calibrationRequired:
           |        meaning: nb:calibrationRequired
           |        description: The machine requires calibration.
           |      maintenanceRequired:
           |        meaning: nb:maintenanceRequired
           |        description: The machine requires maintenance.
           |      qualityIssue:
           |        meaning: nb:qualityIssue
           |        description: A quality issue has been detected.
           |      abnormalNoise:
           |        meaning: nb:abnormalNoise
           |        description: Abnormal noise detected from the machine.
           |      oilChangeRequired:
           |        meaning: nb:oilChangeRequired
           |        description: The machine requires an oil change.
           |slots:
           |  time:
           |    slot_uri: brick:timestamp
           |    range: datetime
           |    description: The timestamp of the observation.
           |  machine:
           |    slot_uri: nb:machine
           |    range: Machine
           |    description: The machine involved in the incident.
           |  incidentType:
           |    slot_uri: nb:incidentType
           |    range: IncidentType
           |    description: The type of the incident.
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix brick: <https://brickschema.org/schema/Brick#> .
          |@prefix nb: <https://neverblink.eu/example/> .
          |@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |nb:IncidentReport a sh:NodeShape;
          |  rdfs:comment "A structured incident report from the shop floor.";
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:datatype xsd:dateTime;
          |      sh:description "The timestamp of the observation.";
          |      sh:maxCount 1;
          |      sh:nodeKind sh:Literal;
          |      sh:order 0;
          |      sh:path brick:timestamp
          |    ], [
          |      sh:class brick:Equipment;
          |      sh:description "The machine involved in the incident.";
          |      sh:maxCount 1;
          |      sh:nodeKind sh:BlankNodeOrIRI;
          |      sh:order 1;
          |      sh:path nb:machine
          |    ], [
          |      sh:description "The type of the incident.";
          |      sh:in (nb:calibrationRequired nb:maintenanceRequired nb:qualityIssue nb:abnormalNoise
          |          nb:oilChangeRequired);
          |      sh:maxCount 1;
          |      sh:order 2;
          |      sh:path nb:incidentType
          |    ];
          |  sh:targetClass nb:IncidentReport .
          |
          |brick:Equipment a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:targetClass brick:Equipment .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "include imported classes by default" in {
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/annotations"),
      )
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using sv).generate(_))
      turtle should include("linkml:Annotatable a sh:NodeShape")
      turtle should include("linkml:Annotation a sh:NodeShape")
      // imported from linkml:extensions
      turtle should include("linkml:Any a sh:NodeShape")
      turtle should include("linkml:Extension a sh:NodeShape")
      turtle should include("linkml:Extensible a sh:NodeShape")
      "sh:NodeShape".r.findAllMatchIn(turtle).size shouldBe 5
    }

    "not include imported classes when onlyClassesFromRootSchema=true" in {
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/annotations"),
      )
      val turtle =
        RdfUtils.toTurtle(
          ShaclGenerator(using sv)
            .generate(_, ShaclGenerator.Options(onlyClassesFromRootSchema = true)),
        )
      turtle should include("linkml:Annotatable a sh:NodeShape")
      turtle should include("linkml:Annotation a sh:NodeShape")
      turtle should not include "linkml:Any a sh:NodeShape"
      turtle should not include "linkml:Extension a sh:NodeShape"
      turtle should not include "linkml:Extensible a sh:NodeShape"
      "sh:NodeShape".r.findAllMatchIn(turtle).size shouldBe 2
    }

    "works for the metamodel annotations and extensions" in {
      val schemaView = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/annotations"),
      )
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      val expected =
        """@prefix linkml: <https://w3id.org/linkml/> .
          |@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
          |@prefix sh: <http://www.w3.org/ns/shacl#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |linkml:Annotatable a sh:NodeShape;
          |  rdfs:comment "mixin for classes that support annotations";
          |  sh:closed false;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:class linkml:Annotation;
          |      sh:description "a collection of tag/text tuples with the semantics of OWL Annotation";
          |      sh:nodeKind sh:BlankNodeOrIRI;
          |      sh:order 0;
          |      sh:path linkml:annotations
          |    ];
          |  sh:targetClass linkml:Annotatable .
          |
          |linkml:Annotation a sh:NodeShape;
          |  rdfs:comment "a tag/value pair with the semantics of OWL Annotation";
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:class linkml:Annotation;
          |      sh:description "a collection of tag/text tuples with the semantics of OWL Annotation";
          |      sh:nodeKind sh:BlankNodeOrIRI;
          |      sh:order 0;
          |      sh:path linkml:annotations
          |    ], [
          |      sh:description "a tag associated with an extension";
          |      sh:maxCount 1;
          |      sh:minCount 1;
          |      sh:nodeKind sh:IRI;
          |      sh:order 1;
          |      sh:path linkml:extension_tag
          |    ], [
          |      sh:description "the actual annotation";
          |      sh:maxCount 1;
          |      sh:minCount 1;
          |      sh:order 2;
          |      sh:path linkml:extension_value
          |    ], [
          |      sh:class linkml:Extension;
          |      sh:description "a tag/text tuple attached to an arbitrary element";
          |      sh:nodeKind sh:BlankNodeOrIRI;
          |      sh:order 3;
          |      sh:path linkml:extensions
          |    ];
          |  sh:targetClass linkml:Annotation .
          |
          |linkml:Any a sh:NodeShape;
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:targetClass linkml:Any .
          |
          |linkml:Extension a sh:NodeShape;
          |  rdfs:comment "a tag/value pair used to add non-model information to an entry";
          |  sh:closed true;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:description "a tag associated with an extension";
          |      sh:maxCount 1;
          |      sh:minCount 1;
          |      sh:nodeKind sh:IRI;
          |      sh:order 0;
          |      sh:path linkml:extension_tag
          |    ], [
          |      sh:description "the actual annotation";
          |      sh:maxCount 1;
          |      sh:minCount 1;
          |      sh:order 1;
          |      sh:path linkml:extension_value
          |    ], [
          |      sh:class linkml:Extension;
          |      sh:description "a tag/text tuple attached to an arbitrary element";
          |      sh:nodeKind sh:BlankNodeOrIRI;
          |      sh:order 2;
          |      sh:path linkml:extensions
          |    ];
          |  sh:targetClass linkml:Extension .
          |
          |linkml:Extensible a sh:NodeShape;
          |  rdfs:comment "mixin for classes that support extension";
          |  sh:closed false;
          |  sh:ignoredProperties (rdf:type);
          |  sh:property [
          |      sh:class linkml:Extension;
          |      sh:description "a tag/text tuple attached to an arbitrary element";
          |      sh:nodeKind sh:BlankNodeOrIRI;
          |      sh:order 0;
          |      sh:path linkml:extensions
          |    ];
          |  sh:targetClass linkml:Extensible .
          |""".stripMargin
      ttlIsomorphic(turtle, expected)
    }

    "generate IRI nodeKind constraints for CURIE types" in {
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using ModelCatalogue.curie.model).generate(_))
      turtle should include("sh:nodeKind sh:IRI")
    }

    "generate IRI nodeKind constraints for URI types" in {
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using ModelCatalogue.uri.model).generate(_))
      turtle should include("sh:nodeKind sh:IRI")
    }

    "generate IRI nodeKind constraints for URI or CURIE types" in {
      val turtle =
        RdfUtils.toTurtle(ShaclGenerator(using ModelCatalogue.uriOrCurie.model).generate(_))
      turtle should include("sh:nodeKind sh:IRI")
    }

    "generate IRI nodeKind constraints for implicitly prefixed slots" in {
      val turtle =
        RdfUtils.toTurtle(ShaclGenerator(using ModelCatalogue.implicitPrefix.model).generate(_))
      turtle should include("sh:nodeKind sh:IRI")
    }

    "ignore identifiers" in {
      val turtle =
        RdfUtils.toTurtle(ShaclGenerator(using ModelCatalogue.reference.model).generate(_))
      turtle should include(
        "sh:ignoredProperties (rdf:type <https://neverblink.eu/linkml/tests/reference/id>)",
      )
    }

    "generate sh:or for any_of" in {
      val turtle =
        RdfUtils.toTurtle(ShaclGenerator(using ModelCatalogue.unionRange.model).generate(_))
      turtle should include("sh:or ")
    }

    "not generate the main range for any_of" in {
      // TODO LNK-129: Get rid of this hack
      val turtle = RdfUtils.toTurtle(
        ShaclGenerator(using ModelCatalogue.unionRangeReference.model).generate(_),
      )
      turtle should include("sh:or ")
      turtle should not include "sh:class <https://neverblink.eu/linkml/tests/unionRangeReference/BaseClass>"
    }

    "emit valid, urlencoded synthetic uris" in {
      val sv = ModelCatalogue.syntheticUris.model
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using sv).generate(_))

      Seq(
        "%C5%81%C4%85czony%28class%29",
        "%C5%82%C4%85czony+%3Ctyp%3E",
        "%C5%82%C4%85czony_%5Bslot%5D",
        "inny_%C5%82%C4%85czony_%22slot%22",
        "%C5%82%C4%85czony_%7Bvalue%7D",
        "inny_%C5%82%C4%85czony_%5C%5Cvalue%2F%2F",
      ).foreach { snippet =>
        turtle should include(snippet)
      }

      Rio.parse(StringReader(turtle), RDFFormat.TURTLE).isEmpty shouldBe false
    }

    "works for the metamodel without runtime exceptions" in {
      val schemaView =
        SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/meta"))
      val turtle = RdfUtils.toTurtle(ShaclGenerator(using schemaView).generate(_))
      turtle.isEmpty shouldBe false
    }

    "work with imported prefixes" in {
      val sv = ModelCatalogue.uriImports.model
      val ttl = RdfUtils.toTurtle(ShaclGenerator(using sv).generate(_))
      Seq(
        "https://neverblink.eu/linkml/tests/uriImports/Class",
        "https://neverblink.eu/linkml/tests/uriImports/slot",
        "https://neverblink.eu/linkml/tests/uriImports/imported/Class",
        "https://neverblink.eu/linkml/tests/uriImports/imported/slot",
      ).foreach { snippet =>
        ttl should include(snippet)
      }
    }

    "generate all catalogue models without errors" when {
      for entry <- ModelCatalogue.all do
        s"model '${entry.model.root.name}'" in {
          assume(skipModels.isEmpty || !skipModels.contains(entry.model.root.name))
          val sink = new CollectingRdfSink
          ShaclGenerator(using entry.model).generate(sink)
          sink.triples should not be empty
          withClue("duplicate triples in the output:") {
            sink.triples.diff(sink.triples.distinct) shouldBe empty
          }
        }
    }
  }
}

object ShaclGeneratorSpec {
  val skipModels: Map[String, String] = Map(
    "typeDesignator" -> "Not yet implemented: LNK-102",
  )
}

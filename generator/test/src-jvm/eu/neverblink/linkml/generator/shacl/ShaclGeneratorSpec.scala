package eu.neverblink.linkml.generator.shacl

import eu.neverblink.linkml.generator.rdf.RdfUtils
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
      SchemaView.loadSchemaViewFromString(schemaYaml)

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
      val rules = ShaclGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rules)
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
      val rules = ShaclGenerator(using schemaView).generate(enforceOpenShapes = true)
      val turtle = RdfUtils.toTurtle(rules)
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
      val rules = ShaclGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rules)
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
      val rules = ShaclGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rules)
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
      val rules = ShaclGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rules)
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
      val rules = ShaclGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rules)
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
      val rules = ShaclGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rules)
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
      val rules = ShaclGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rules)
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
      val sv = SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/annotations")
      val shacl = ShaclGenerator(using sv).generate()
      val turtle = RdfUtils.toTurtle(shacl)
      turtle should include("linkml:Annotatable a sh:NodeShape")
      turtle should include("linkml:Annotation a sh:NodeShape")
      // imported from linkml:extensions
      turtle should include("linkml:Any a sh:NodeShape")
      turtle should include("linkml:Extension a sh:NodeShape")
      turtle should include("linkml:Extensible a sh:NodeShape")
      "sh:NodeShape".r.findAllMatchIn(turtle).size shouldBe 5
    }

    "not include imported classes when onlyClassesFromRootSchema=true" in {
      val sv = SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/annotations")
      val shacl = ShaclGenerator(using sv).generate(onlyClassesFromRootSchema = true)
      val turtle = RdfUtils.toTurtle(shacl)
      turtle should include("linkml:Annotatable a sh:NodeShape")
      turtle should include("linkml:Annotation a sh:NodeShape")
      turtle should not include "linkml:Any a sh:NodeShape"
      turtle should not include "linkml:Extension a sh:NodeShape"
      turtle should not include "linkml:Extensible a sh:NodeShape"
      "sh:NodeShape".r.findAllMatchIn(turtle).size shouldBe 2
    }

    "works for the metamodel annotations and extensions" in {
      val schemaView = SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/annotations")
      val rules = ShaclGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rules)
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
      val result = ShaclGenerator(using ModelCatalogue.curie.model).generate()
      val turtle = RdfUtils.toTurtle(result)
      turtle should include("sh:nodeKind sh:IRI")
    }

    "generate IRI nodeKind constraints for URI types" in {
      val result = ShaclGenerator(using ModelCatalogue.uri.model).generate()
      val turtle = RdfUtils.toTurtle(result)
      turtle should include("sh:nodeKind sh:IRI")
    }

    "generate IRI nodeKind constraints for URI or CURIE types" in {
      val result = ShaclGenerator(using ModelCatalogue.uriOrCurie.model).generate()
      val turtle = RdfUtils.toTurtle(result)
      turtle should include("sh:nodeKind sh:IRI")
    }

    "generate IRI nodeKind constraints for implicitly prefixed slots" in {
      val result = ShaclGenerator(using ModelCatalogue.implicitPrefix.model).generate()
      val turtle = RdfUtils.toTurtle(result)
      turtle should include("sh:nodeKind sh:IRI")
    }

    "ignore identifiers" in {
      val result = ShaclGenerator(using ModelCatalogue.reference.model).generate()
      val turtle = RdfUtils.toTurtle(result)
      turtle should include(
        "sh:ignoredProperties (rdf:type <https://neverblink.eu/linkml/tests/reference/id>)",
      )
    }

    "generate sh:or for any_of" in {
      val result = ShaclGenerator(using ModelCatalogue.unionRange.model).generate()
      val turtle = RdfUtils.toTurtle(result)
      turtle should include(
        "sh:or ",
      )
    }

    "works for the metamodel without runtime exceptions" in {
      val schemaView = SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/meta")
      val rules = ShaclGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rules)
      turtle.isEmpty shouldBe false
    }

    "work with imported prefixes" in {
      val sv = ModelCatalogue.uriImports.model
      val rules = ShaclGenerator(using sv).generate()
      val ttl = RdfUtils.toTurtle(rules)
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
          ShaclGenerator(using entry.model).generate()._2 should not be empty
        }
    }
  }
}

object ShaclGeneratorSpec {
  val skipModels: Map[String, String] = Map(
    "typeDesignator" -> "Not yet implemented: LNK-102",
  )
}

package eu.neverblink.linkml.generator.rdfs

import eu.neverblink.linkml.generator.rdf.RdfUtils
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RdfsGeneratorSpec extends AnyWordSpec, Matchers {
  "RdfsGenerator" should {
    def loadWithImports(schemaYaml: String): SchemaView =
      SchemaView.loadSchemaViewFromString(schemaYaml)

    // Shared part of the schema
    val schemaShared =
      """id: https://neverblink.eu/linkml/rdfs/test/
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
      val rdfs = RdfsGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rdfs)
      turtle shouldBe
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/SomeClass> a rdfs:Class .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_slot> a rdf:Property;
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/SomeClass>;
          |  rdfs:range xsd:string .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_other_slot> a rdf:Property;
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/SomeClass>;
          |  rdfs:range xsd:integer .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_yet_another_slot> a rdf:Property;
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/SomeClass>;
          |  rdfs:range xsd:boolean .
          |""".stripMargin
    }

    "emit RDFS label for LinkML titles" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    tree_root: true
           |    title: Some class
           |    slots:
           |    - some_slot
           |    - some_yet_another_slot
           |  SomeAnotherClass:
           |    slots:
           |    - some_other_slot
           |slots:
           |  some_slot:
           |    title: String
           |    range: string
           |  some_other_slot:
           |    range: integer
           |  some_yet_another_slot:
           |    range: SomeAnotherClass
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val rdfs = RdfsGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rdfs)
      turtle shouldBe
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/SomeClass> a rdfs:Class;
          |  rdfs:label "Some class" .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_slot> a rdf:Property;
          |  rdfs:label "String";
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/SomeClass>;
          |  rdfs:range xsd:string .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_yet_another_slot> a rdf:Property;
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/SomeClass>;
          |  rdfs:range <https://neverblink.eu/linkml/rdfs/test/SomeAnotherClass> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/SomeAnotherClass> a rdfs:Class .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_other_slot> a rdf:Property;
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/SomeAnotherClass>;
          |  rdfs:range xsd:integer .
          |""".stripMargin
    }

    "classes with inheritance" in {
      val input =
        s"""$schemaShared
           |classes:
           |  Person:
           |    description: Represents a person.
           |    tree_root: true
           |  Course:
           |    description: Represents a course.
           |  Employee:
           |    description: Represents an employee.
           |    is_a: Person
           |    slots:
           |      - worksFor
           |  Professor:
           |    description: Represents a professor.
           |    is_a: Employee
           |    slots:
           |      - teaches
           |slots:
           |  teaches:
           |    description: Property indicating which course a professor teaches.
           |    range: Course
           |  worksFor:
           |    description: Property indicating who an employee works for.
           |    range: Person
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val rdfs = RdfsGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rdfs)
      turtle shouldBe
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Person> a rdfs:Class;
          |  rdfs:comment "Represents a person." .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Course> a rdfs:Class;
          |  rdfs:comment "Represents a course." .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Employee> a rdfs:Class;
          |  rdfs:comment "Represents an employee.";
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/Person> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/worksFor> a rdf:Property;
          |  rdfs:comment "Property indicating who an employee works for.";
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/Employee>, <https://neverblink.eu/linkml/rdfs/test/Professor>;
          |  rdfs:range <https://neverblink.eu/linkml/rdfs/test/Person> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Professor> a rdfs:Class;
          |  rdfs:comment "Represents a professor.";
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/Employee> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/teaches> a rdf:Property;
          |  rdfs:comment "Property indicating which course a professor teaches.";
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/Professor>;
          |  rdfs:range <https://neverblink.eu/linkml/rdfs/test/Course> .
          |""".stripMargin
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
      val rdfs = RdfsGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rdfs)
      turtle shouldBe
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Node> a rdfs:Class .
          |
          |<https://neverblink.eu/linkml/rdfs/test/name> a rdf:Property;
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/Node>;
          |  rdfs:range xsd:time .
          |
          |<https://neverblink.eu/linkml/rdfs/test/children> a rdf:Property;
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/Node>;
          |  rdfs:range <https://neverblink.eu/linkml/rdfs/test/Node> .
          |""".stripMargin
    }

    "work for mixins and diamond inheritance" in {
      val input =
        s"""$schemaShared
           |classes:
           |  MotorVehicle:
           |  PassengerVehicle:
           |    is_a: MotorVehicle
           |  Van:
           |    is_a: MotorVehicle
           |  Truck:
           |    is_a: MotorVehicle
           |  MiniVan:
           |    is_a: PassengerVehicle
           |    mixins:
           |      - Van
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val rdfs = RdfsGenerator(using schemaView).generate()
      val turtle = RdfUtils.toTurtle(rdfs)
      turtle shouldBe
        """@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
          |@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
          |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/PassengerVehicle> a rdfs:Class;
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/MotorVehicle> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/MotorVehicle> a rdfs:Class .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Truck> a rdfs:Class;
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/MotorVehicle> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/MiniVan> a rdfs:Class;
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/PassengerVehicle>, <https://neverblink.eu/linkml/rdfs/test/Van> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Van> a rdfs:Class;
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/MotorVehicle> .
          |""".stripMargin
    }

    "include imported classes by default" in {
      val sv = SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/annotations")
      val rdfs = RdfsGenerator(using sv).generate()
      val turtle = RdfUtils.toTurtle(rdfs)
      turtle should include("linkml:Annotatable a rdfs:Class")
      turtle should include("linkml:Annotation a rdfs:Class")
      // imported from linkml:extensions
      turtle should include("linkml:Any a rdfs:Class")
      turtle should include("linkml:Extension a rdfs:Class")
      turtle should include("linkml:Extensible a rdfs:Class")
      "rdfs:Class".r.findAllMatchIn(turtle).size shouldBe 5
    }

    "not include imported classes when onlyClassesFromRootSchema=true" in {
      val sv = SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/annotations")
      val rdfs = RdfsGenerator(using sv).generate(onlyClassesFromRootSchema = true)
      val turtle = RdfUtils.toTurtle(rdfs)
      turtle should include("linkml:Annotatable a rdfs:Class")
      turtle should include("linkml:Annotation a rdfs:Class")
      turtle should not include "linkml:Any a rdfs:Class"
      turtle should not include "linkml:Extension a rdfs:Class"
      turtle should not include "linkml:Extensible a rdfs:Class"
      "rdfs:Class".r.findAllMatchIn(turtle).size shouldBe 2
    }

    "generate all catalogue models without errors" when {
      for entry <- ModelCatalogue.all do
        s"model '${entry.model.root.name}'" in {
          RdfsGenerator(using entry.model).generate()._2 should not be empty
        }
    }
  }
}

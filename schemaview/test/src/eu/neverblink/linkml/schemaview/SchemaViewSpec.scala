package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml

import scala.collection.mutable
import scala.util.{Failure, Success}

class SchemaViewSpec extends AnyWordSpec, Matchers {
  import SchemaViewSpec.*

  "SchemaView" should {
    val sv = SchemaView.single(schema)
    given SchemaView = sv
    val sep = System.getProperty("file.separator")
    val cwd = PlatformSpecificUtils.getEnv("MILL_WORKSPACE_ROOT")
      .getOrElse(PlatformSpecificUtils.cwd) // fallback for IDE runs

    "not allow being created from no schemas" in {
      intercept[IllegalArgumentException] {
        SchemaView(Seq.empty)
      }.getMessage should include("At least one schema")
    }
    "resolve classes by reference" in {
      Reference[ClassDefinition]("class1").resolve shouldBe Some(class1)
      Reference[ClassDefinition]("class2").resolve shouldBe Some(class2)
      Reference[ClassDefinition]("class3").resolve shouldBe Some(class3)
    }

    "resolve slots by reference" in {
      Reference[SlotDefinition]("slot1").resolve shouldBe Some(slot1)
      Reference[SlotDefinition]("slot2").resolve shouldBe Some(slot2)
      Reference[SlotDefinition]("slot_t1").resolve shouldBe Some(slot_t1)
      Reference[SlotDefinition]("slot_e1").resolve shouldBe Some(slot_e1)
    }

    "resolve types" in {
      Reference[TypeDefinition]("type1").resolve shouldBe Some(type1)
    }

    "resolve enums" in {
      Reference[EnumDefinition]("enum1").resolve shouldBe Some(enum1)
    }

    "resolve subsets" in {
      Reference[SubsetDefinition]("subset1").resolve shouldBe Some(subset1)
    }

    "resolve slot ranges" in {
      slot1.range.get.resolve shouldBe Some(class1)
      slot2.range.get.resolve shouldBe Some(class2)
      slot3.range.get.resolve shouldBe Some(class3)
      slot_t1.range.get.resolve shouldBe Some(type1)
      slot_e1.range.get.resolve shouldBe Some(enum1)
    }

    "resolve class slot references" in {
      val resolvedSlots = for el <- class1.slots yield el.resolve.get
      resolvedSlots should contain theSameElementsAs Seq(slot2, slot_e1)
    }

    "resolve inSubset" in {
      class1.inSubset.head.resolve shouldBe Some(subset1)
    }

    "walk the references" in {
      Reference[ClassDefinition]("class1").resolve.get
        .slots.find(_.value == "slot2").get.resolve.get
        .range.get.resolve.get
        .asInstanceOf[ClassDefinition]
        .slots.find(_.value == "slot1").get.resolve.get
    }

    "not compile" in {
      "given SchemaView = ???; Reference[ClassExpression](\"test\").resolve" shouldNot compile
    }

    "find the parents of classes" in {
      Reference[ClassView]("class_child").resolve.get
        .parents.map(_.cls).toSeq should contain theSameElementsInOrderAs Seq(
        mixin,
        classParent,
      )

      Reference[ClassView]("class_parent").resolve.get
        .parents.map(_.cls).toSeq should contain theSameElementsInOrderAs Seq(
        parentMixin1,
        parentMixin2,
        classGrandparent,
      )
    }

    "find the ancestors of a class" in {
      Reference[ClassView]("class_child").resolve.get
        .ancestors(false).map(_.cls).toSeq should contain theSameElementsInOrderAs Seq(
        mixin,
        classParent,
        parentMixin1,
        parentMixin2,
        classGrandparent,
      )
    }

    "find the ancestors of a class (reflexive)" in {
      Reference[ClassView]("class_child").resolve.get
        .ancestors(true).map(_.cls).toSeq should contain theSameElementsInOrderAs Seq(
        classChild,
        mixin,
        classParent,
        parentMixin1,
        parentMixin2,
        classGrandparent,
      )
    }

    "find the ancestors of a class in correct order" in {
      Reference[ClassView]("class_mixin_inheritance").resolve.get
        .ancestors(false).map(_.cls).toSeq should contain theSameElementsInOrderAs Seq(
        mixinChild, // .mixins[0]
        classParent, // .is_a
        parentMixin1, // is_a.mixins[0]
        parentMixin2, // is_a.mixins[1]
        classGrandparent, // is_a.is_a
        mixinParent, // .mixins[0].is_a
      )
    }

    "find the parents of a slot" in {
      Reference[SlotView]("slot3").resolve.get
        .parents.toSeq should contain theSameElementsInOrderAs Seq(
        slotParent,
      )
    }

    "find the ancestors of a slot" in {
      Reference[SlotView]("slot3").resolve.get
        .ancestors(false).toSeq should contain theSameElementsInOrderAs Seq(
        slotParent,
        slotGrandparent,
      )
    }

    "find the ancestors of a slot (reflexive)" in {
      Reference[SlotView]("slot3").resolve.get
        .ancestors(true).toSeq should contain theSameElementsInOrderAs Seq(
        slot3,
        slotParent,
        slotGrandparent,
      )
    }

    "infer URIs" in {
      sv.classes("class1").uriStr shouldBe "https://neverblink.eu/example#Class1"
      sv.slotDefinitions("slot1").uriStr shouldBe "https://neverblink.eu/example#slot1"
      sv.enums("enum1").uriStr shouldBe "https://neverblink.eu/example#Enum1"
    }

    "use the provided URIs if available" in {
      sv.classes("class2").uriStr shouldBe "https://neverblink.eu/example#classNumberTwo"
      sv.slotDefinitions("slot2").uriStr shouldBe "https://neverblink.eu/example#slotNumberTwo"
      sv.enums("enum2").uriStr shouldBe "https://neverblink.eu/example#enumNumberTwo"
    }

    "use fallback default prefix" in {
      val sv2 = SchemaView.single(schemaFallbackful)
      val cls = sv2.classes("class1")
      cls.defaultPrefixUri shouldBe "http://schema2/"
      cls.uriStr shouldBe "http://schema2/Class1"
    }

    "use fallback default prefix (schema uri already terminated with #)" in {
      val schemaWithHash = schemaFallbackful.copy(id = UriOrCurie("http://schema2#"))
      val sv2 = SchemaView.single(schemaWithHash)
      val cls = sv2.classes("class1")
      cls.defaultPrefixUri shouldBe "http://schema2#"
      cls.uriStr shouldBe "http://schema2#Class1"
    }

    "use fallback default prefix (schema uri already terminated with /)" in {
      val schemaWithSlash = schemaFallbackful.copy(id = UriOrCurie("http://schema2/"))
      val sv2 = SchemaView.single(schemaWithSlash)
      val cls = sv2.classes("class1")
      cls.defaultPrefixUri shouldBe "http://schema2/"
      cls.uriStr shouldBe "http://schema2/Class1"
    }

    "infer ranges if missing" in {
      sv.slotDefinitions("slot_rangeless").derivedRange shouldBe Reference("type1")
    }

    "infer ranges with the string type fallback" in {
      SchemaView.single(schemaFallbackful).slotDefinitions(
        "slot_rangeless",
      ).derivedRange shouldBe Reference("string")
    }

    "load schema with diamond imports and relative references" in {
      val schemaAStr =
        """
          |id: a
          |name: a
          |imports:
          |  - level1/b
          |  - level1/c
          |""".stripMargin
      val schemaBStr =
        """
          |id: b
          |name: b
          |imports:
          |  - level2/d
          |""".stripMargin
      val schemaCStr =
        """
          |id: c
          |name: c
          |imports:
          |  - level2/d
          |""".stripMargin
      val schemaDStr =
        """
          |id: d
          |name: d
          |""".stripMargin
      val schemaD = parse(schemaDStr)
      val schemaC = parse(schemaCStr)
      val schemaB = parse(schemaBStr)
      val schemaA = parse(schemaAStr)
      SchemaView.loadSchemaViewFromUri(s"${cwd}${sep}schemaview/test/resources/a.yaml") shouldBe
        SchemaView(Seq(schemaA, schemaB, schemaD, schemaC))
      // Should also work with the .yml extension
      SchemaView.loadSchemaViewFromUri(s"${cwd}${sep}schemaview/test/resources/a.yml") shouldBe
        SchemaView(Seq(schemaA, schemaB, schemaD, schemaC))
    }
    "load imported schemas only once" in {
      val imported = mutable.ArrayBuffer[String]()
      val importer = new StringImporter {
        def read(path: String): String =
          imported.addOne(path)
          FileSystemImporter.read(path)
      }

      val sv = SchemaView.loadSchemaViewFromUri(
        s"${cwd}${sep}schemaview/test/resources/a.yaml",
        importer = importer,
      )
      sv.schemas.size shouldBe 4
      // Each path should be attempted only once.
      imported.size shouldBe 4
    }
    "not get caught up in circular imports" in {
      val sv = SchemaView.loadSchemaViewFromUri(
        s"${cwd}${sep}schemaview/test/resources/loop/a.yaml",
      )
      // it's a->b->c->a, but we should only see each schema once
      sv.schemas.size shouldBe 3
    }
    "load schema with imports from inlined resources" in {
      val expected = Seq(
        loadSchemaResource("/meta.yaml"),
        loadSchemaResource("/types.yaml"),
        loadSchemaResource("/mappings.yaml"),
        loadSchemaResource("/extensions.yaml"),
        loadSchemaResource("/annotations.yaml"),
        loadSchemaResource("/units.yaml"),
      )
      SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/meta").schemas shouldBe expected
    }
    "load schema from a file with imports from inlined resources" in {
      val linkmlTypes = loadSchemaResource("/types.yaml")

      SchemaView.loadSchemaViewFromUri(
        s"${cwd}${sep}schemaview${sep}test${sep}resources${sep}importBundled.yaml",
      ).schemas should contain (linkmlTypes)
      SchemaView.loadSchemaViewFromUri(
        s"${cwd}${sep}schemaview${sep}test${sep}resources${sep}importBundled.yaml",
      ).schemas should contain (linkmlTypes)
    }
    "resolve default uris and ranges of classes, slots, types, enums in imported schemas" in {
      val sv =
        SchemaView.loadSchemaViewFromUri(
          s"${cwd}${sep}schemaview/test/resources/import_uris/main.yaml",
        )
      sv.classes.size shouldBe 2
      sv.slotDefinitions.size shouldBe 4
      sv.types.size shouldBe 21
      sv.enums.size shouldBe 2
      sv.classes("main_class").uriStr shouldBe "https://neverblink.eu/main#MainClass"
      sv.classes("imported_class").uriStr shouldBe "https://neverblink.eu/imported#ImportedClass"
      sv.slotDefinitions("main_slot").uriStr shouldBe "https://neverblink.eu/main#main_slot"
      sv.slotDefinitions("main_slot").derivedRange.value shouldBe "double"
      sv.slotDefinitions("imported_slot")
        .uriStr shouldBe "https://neverblink.eu/imported#imported_slot"
      // Default range of the importing schema should not override the default range of the imported slot
      sv.slotDefinitions("imported_slot").derivedRange.value shouldBe "integer"
      sv.types("main_type").uriStr shouldBe "https://neverblink.eu/main#main_type"
      sv.types("imported_type").uriStr shouldBe "https://neverblink.eu/imported#imported_type"
      sv.enums("main_enum").uriStr shouldBe "https://neverblink.eu/main#MainEnum"
      sv.enums("imported_enum").uriStr shouldBe "https://neverblink.eu/imported#ImportedEnum"
      // Ranges and default prefixes of derived slots should use the context of their original
      // schema.
      val attrs = sv.classes("main_class").derivedAttributes
      attrs.size shouldBe 5
      val cases = Seq(
        // slot defined in imported class, inherited
        "imported_slot",
        // slot defined in imported class, specialized in importing class with slot_usage
        "slot_to_be_specialized",
        // slot defined in imported schema, used in importing class with slots
        "slot_to_be_used",
        // attr defined in imported class, inherited without specialization
        "attribute_to_leave_unchanged",
        // attr defined in imported class, specialized in importing class with slot_usage
        "attribute_to_be_specialized",
      )
      for attrName <- cases do
        withClue("for derived attribute " + attrName) {
          val attr = attrs(attrName)
          attr.derivedRange.value shouldBe "integer"
          attr.defaultPrefixUri shouldBe "https://neverblink.eu/imported#"
          attr.uriStr shouldBe s"https://neverblink.eu/imported#$attrName"
          if attrName.endsWith("specialized") then
            // slot_usage should be applied
            attr.slot.required shouldBe true
        }
    }
    "not relativize URLs and URNs in imports" in {
      val schemaStr =
        """
          |id: a
          |name: a
          |imports:
          |  - relative
          |  - https://absolute.org/schema
          |  - http://absolute.org/schema2
          |  - file:///file/path/schema
          |  - ftp://ftp/path/schema
          |  - urn:example:schema
          |""".stripMargin

      val emptySchemaStr =
        """id: empty
          |name: empty
          |""".stripMargin

      val schema = FileSystemImporter.parseSchema(schemaStr)
      val emptySchema = FileSystemImporter.parseSchema(emptySchemaStr)
      val touchedPaths = mutable.ArrayBuffer[String]()
      val importer = new Importer {
        def readSchema(path: String): SchemaDefinition = {
          touchedPaths.addOne(path)
          emptySchema
        }
      }
      SchemaView.loadImports(schema, baseUri = "http://base.org/schema", importer = importer)
      touchedPaths should contain theSameElementsAs Seq(
        "http://base.org/schema/relative.yaml",
        "https://absolute.org/schema.yaml",
        "http://absolute.org/schema2.yaml",
        "file:///file/path/schema.yaml",
        "ftp://ftp/path/schema.yaml",
        "urn:example:schema.yaml",
      )
    }
    "link attribute definitions to their original schemas" in {
      val model =
        """id: http://example.com/c
          |name: c
          |prefixes:
          |  linkml: https://w3id.org/linkml/
          |imports:
          |  - linkml:types
          |classes:
          |  SomeClass:
          |    attributes:
          |      id: { identifier: true }
          |""".stripMargin
      val sv = SchemaView.loadSchemaViewFromString(model)
      val attr = sv.classes("SomeClass").derivedAttributes("id")
      attr.slot.identifier shouldBe true
      attr.definingSchema shouldBe sv.root
    }
    "find the tree_root" in {
      val model = SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/meta")
      val root = model.treeRoot
      root.isDefined shouldBe true
      root.get.cls.name shouldBe "schema_definition"

      val root2 = model.treeRootWithOverride(None)
      root2 match {
        case Failure(exception) => fail(exception)
        case Success(value) => value shouldBe root
      }
    }
    "not find the tree_root if it's not present" in {
      val root = sv.treeRoot
      root.isEmpty shouldBe true
    }
    "not find the tree root if it's in the imports" in {
      val schemaMain =
        """id: main
           |name: main
           |""".stripMargin
      val schemaImported =
        """id: imported
           |name: imported
           |classes:
           |  someRoot:
           |    tree_root: true
           |""".stripMargin
      val sv = SchemaView(
        Seq(
          FileSystemImporter.parseSchema(schemaMain),
          FileSystemImporter.parseSchema(schemaImported),
        ),
      )
      sv.treeRoot shouldBe None
      sv.classes("someRoot").cls.treeRoot shouldBe true
    }
    "allow overriding the tree_root" in {
      val root = sv.treeRootWithOverride(Some("class1"))
      root match {
        case Failure(exception) => fail(exception)
        case Success(value) => value.get.cls shouldBe class1
      }
    }
    "determine reachable classes" in {
      val r1 =
        sv.classesReachableFrom(sv.classes("class1"), includeAncestors = false, inlinedOnly = false)
      val r2 =
        sv.classesReachableFrom(sv.classes("class1"), includeAncestors = true, inlinedOnly = false)
      r1.keys should contain theSameElementsAs Seq("class1", "class2")
      r2.keys should contain theSameElementsAs Seq("class1", "class2")
      // class2 has as id slot, so it should not be inlined
      val r1Inlined = sv.classesReachableFrom(
        sv.classes("class1"),
        includeAncestors = false,
        inlinedOnly = true,
      )
      r1Inlined.keys should contain theSameElementsAs Seq("class1")
      val r3 =
        sv.classesReachableFrom(
          sv.classes("class_mixin_inheritance"),
          includeAncestors = false,
          inlinedOnly = false,
        )
      r3.keys should contain theSameElementsAs Seq("class_mixin_inheritance")
      val r4 =
        sv.classesReachableFrom(
          sv.classes("class_mixin_inheritance"),
          includeAncestors = true,
          inlinedOnly = false,
        )
      r4.keys should contain theSameElementsAs Seq(
        "class_mixin_inheritance",
        "mixin_parent",
        "class_grandparent",
        "mixin_parent_1",
        "mixin_parent_2",
        "mixin_child",
        "class_parent",
      )
    }
    "recognize the tree_root inline mode from the extension" in {
      val model =
        """id: http://example.com/c
            |name: c
            |classes:
            |  NoExt:
            |    tree_root: true
            |  ExtPlain:
            |    extensions:
            |      tree_root_as: plain
            |  ExtOptional:
            |    extensions:
            |      tree_root_as: optional
            |  ExtList:
            |    extensions:
            |      tree_root_as: list
            |  ExtUnknown:
            |    extensions:
            |      tree_root_as: unknown
            |""".stripMargin
      val sv = SchemaView.single(parse(model))
      sv.classes("NoExt").treeRootInlineType(None) shouldBe InlineType.plain
      sv.classes("ExtPlain").treeRootInlineType(None) shouldBe InlineType.plain
      sv.classes("ExtOptional").treeRootInlineType(None) shouldBe InlineType.optional
      sv.classes("ExtList").treeRootInlineType(None) shouldBe InlineType.list
      val ex = intercept[IllegalArgumentException] {
        sv.classes("ExtUnknown").treeRootInlineType(None)
      }
      ex.getMessage should include("Unknown tree_root_as extension value: 'unknown'")
    }
    "return failure if the tree_root override is invalid" in {
      val root = sv.treeRootWithOverride(Some("nonexistent_class"))
      root match {
        case Failure(exception) =>
          exception.getMessage should
            include("Could not find class 'nonexistent_class'")
        case Success(_) => fail("Expected failure but got success")
      }
    }

    def loadSchemaResource(resource: String): SchemaDefinition = parse(Resources.read(resource))

    def parse(content: String): SchemaDefinition = parseYaml(content) match {
      case Right(node) => Codec.codec.decode(node)
      case Left(err) => throw err
    }
  }
}

object SchemaViewSpec:
  extension [T <: Element](el: T)
    def compact: (String, T) = el.name -> el
    def reference: Reference[T] = Reference(el.name)

  val class1: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "class1",
    description = Some("This is a first class"),
    slots = Seq(
      Reference("slot2"),
      Reference("slot_e1"),
    ),
    inSubset = Seq(
      Reference("subset1"),
    ),
  )

  val class2: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "class2",
    classUri = Some(UriOrCurie("https://neverblink.eu/example#classNumberTwo")),
    description = Some("This is a second class"),
    slots = Seq(
      Reference("slot1"),
      Reference("slot_t1"),
      Reference("id"), // has ID, should not be inlined
    ),
  )

  val class3: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "class3",
    classUri = Some(UriOrCurie("https://neverblink.eu/example#classNumber3")),
    description = Some("This is a third class"),
    slots = Seq(
      Reference("slot3"),
    ),
  )

  val classGrandparent: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "class_grandparent",
    `abstract` = true,
  )

  val parentMixin1: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "mixin_parent_1",
    mixin = true,
  )

  val parentMixin2: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "mixin_parent_2",
    mixin = true,
  )

  val classParent: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "class_parent",
    isA = Some(classGrandparent.reference),
    mixins = Seq(parentMixin1.reference, parentMixin2.reference),
  )

  val mixin: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "mixin",
    mixin = true,
  )

  val classChild: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "class_child",
    isA = Some(classParent.reference),
    mixins = Seq(mixin.reference),
  )

  val mixinParent: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "mixin_parent",
    mixin = true,
  )

  val mixinChild: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "mixin_child",
    isA = Some(mixinParent.reference),
    mixin = true,
  )

  val classWithMixinInheritance: ClassDefinitionImpl = ClassDefinitionImpl(
    name = "class_mixin_inheritance",
    isA = Some(classParent.reference),
    mixins = Seq(mixinChild.reference),
  )

  val classes: Map[String, ClassDefinitionImpl] = Map(
    class1.compact,
    class2.compact,
    class3.compact,
    classGrandparent.compact,
    classParent.compact,
    parentMixin1.compact,
    parentMixin2.compact,
    classChild.compact,
    mixin.compact,
    classWithMixinInheritance.compact,
    mixinParent.compact,
    mixinChild.compact,
  )

  val slotId: SlotDefinitionImpl = SlotDefinitionImpl(
    name = "id",
    identifier = true,
  )

  val slot1: SlotDefinitionImpl = SlotDefinitionImpl(
    name = "slot1",
    description = Some("This is a slot pointing to class 1"),
    range = Some(Reference("class1")),
  )

  val slot2: SlotDefinitionImpl = SlotDefinitionImpl(
    name = "slot2",
    slotUri = Some(UriOrCurie("https://neverblink.eu/example#slotNumberTwo")),
    description = Some("This is a slot pointing to class 2"),
    range = Some(Reference("class2")),
  )

  val slotGrandparent: SlotDefinitionImpl = SlotDefinitionImpl(
    name = "slot_grandparent",
    description = Some("This is a slot pointing to class grandparent"),
    range = Some(classGrandparent.reference),
  )

  val slotParent: SlotDefinitionImpl = SlotDefinitionImpl(
    name = "slot_parent",
    isA = Some(slotGrandparent.reference),
    description = Some("This is a slot pointing to class parent"),
    range = Some(classParent.reference),
  )

  val slot3: SlotDefinitionImpl = SlotDefinitionImpl(
    name = "slot3",
    isA = Some(slotParent.reference),
    description = Some("This is a slot pointing to class 3"),
    range = Some(Reference("class3")),
  )

  val slot_t1: SlotDefinitionImpl = SlotDefinitionImpl(
    name = "slot_t1",
    description = Some("This is a slot pointing to type 1"),
    range = Some(Reference("type1")),
  )

  val slot_e1: SlotDefinitionImpl = SlotDefinitionImpl(
    name = "slot_e1",
    description = Some("This is a slot pointing to enum 1"),
    range = Some(Reference("enum1")),
  )

  val slotRangeless: SlotDefinitionImpl = SlotDefinitionImpl(
    name = "slot_rangeless",
    description = Some("This slot should use the default range of the schema."),
  )

  val slots: Map[String, SlotDefinitionImpl] = Map(
    slotId.compact,
    slot1.compact,
    slot2.compact,
    slotGrandparent.compact,
    slotParent.compact,
    slot3.compact,
    slot_t1.compact,
    slot_e1.compact,
    slotRangeless.compact,
  )

  val type1: TypeDefinitionImpl = TypeDefinitionImpl(
    name = "type1",
    description = Some("Type 1"),
  )

  val types: Map[String, TypeDefinitionImpl] = Map(
    type1.compact,
  )

  val enum1: EnumDefinitionImpl = EnumDefinitionImpl(
    name = "enum1",
    description = Some("Enum 1"),
  )

  val enum2: EnumDefinitionImpl = EnumDefinitionImpl(
    name = "enum2",
    enumUri = Some(UriOrCurie("https://neverblink.eu/example#enumNumberTwo")),
    description = Some("Enum 2"),
  )

  val enums: Map[String, EnumDefinitionImpl] = Map(
    enum1.compact,
    enum2.compact,
  )

  val subset1: SubsetDefinitionImpl = SubsetDefinitionImpl(
    name = "subset1",
    description = Some("Subset 1"),
  )

  val subsets: Map[String, SubsetDefinitionImpl] = Map(
    subset1.compact,
  )

  val schema: SchemaDefinitionImpl = SchemaDefinitionImpl(
    id = UriOrCurie("schema1"),
    name = "Schema of id schema1",
    prefixes = Map(
      "default" -> PrefixImpl("default", UriOrCurie("https://neverblink.eu/example#")),
    ),
    defaultPrefix = Some("default"),
    defaultRange = Some(Reference("type1")),
    classes = classes,
    slotDefinitions = slots,
    types = types,
    enums = enums,
    subsets = subsets,
  )

  val schemaFallbackful: SchemaDefinitionImpl = SchemaDefinitionImpl(
    id = UriOrCurie("http://schema2"),
    name = "Schema of id schema2",
    classes = classes,
    slotDefinitions = slots,
    types = types + ("string" -> TypeDefinitionImpl(name = "string")),
    enums = enums,
    subsets = subsets,
  )

  val schemaInvalid: SchemaDefinitionImpl = SchemaDefinitionImpl(
    id = UriOrCurie("schema3"),
    name = "Schema of id schema3",
    classes = classes,
    slotDefinitions = slots,
    types = types,
    enums = enums,
    subsets = subsets,
  )

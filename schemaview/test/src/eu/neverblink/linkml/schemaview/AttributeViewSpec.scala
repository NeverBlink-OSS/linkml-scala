package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.runtime.{Curie, Uri}
import eu.neverblink.linkml.schemaview.InlineType.plain
import eu.neverblink.linkml.schemaview.SubjectType.{base, implicitPrefix}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.reflect.ClassTag

class AttributeViewSpec extends AnyWordSpec, Matchers {

  extension [T](t: T)
    def shouldBeA[X: ClassTag]: X = {
      t shouldBe a[X]
      t.asInstanceOf[X]
    }

  "ClassView.attributeViews" should {
    val schema =
      """id: urn:test
        |name: test
        |
        |imports:
        | - linkml:types
        | - linkml:extended_types
        |
        |classes:
        |  C1:
        |    attributes:
        |      any:
        |        slot_uri: urn:any
        |        range: Any
        |      inline:
        |        slot_uri: urn:inline
        |        range: C2
        |        inlined: true
        |        required: true
        |      ref:
        |        slot_uri: urn:ref
        |        range: C2
        |        required: true
        |      type:
        |        slot_uri: urn:type
        |        range: string
        |        required: true
        |      enum:
        |        slot_uri: urn:enum
        |        range: E1
        |        required: true
        |  C2:
        |    class_uri: urn:C2
        |    attributes:
        |      id:
        |        slot_uri: urn:id
        |        range: string
        |        identifier: true
        |enums:
        |  E1:
        |    enum_uri: urn:E1
        |    permissible_values:
        |      V1:
        |      V2:
        |""".stripMargin

    lazy val sv = SchemaView.loadSchemaViewFromString(schema)
    lazy val c1 = sv.classes("C1")

    "provide AnyViews for classes with linkml:Any uri" in {
      c1.attributeViews("any")
        .shouldBeA[AnyView]
        .slotView.uriOrCurie shouldBe Uri("urn:any")
    }

    "provide ClassInlineAttributeView for inlined classes" in {
      val inline = c1.attributeViews("inline")
        .shouldBeA[ClassInlineAttributeView]
      inline.slotView.uriOrCurie shouldBe Uri("urn:inline")
      inline.classView.uriOrCurie shouldBe Uri("urn:C2")
      inline.inlineType shouldBe plain
    }

    "provide ClassReferenceAttributeView for referenced classes" in {
      val ref = c1.attributeViews("ref")
        .shouldBeA[ClassReferenceAttributeView]
      ref.slotView.uriOrCurie shouldBe Uri("urn:ref")
      ref.classView.uriOrCurie shouldBe Uri("urn:C2")
      ref.identifierView.slotView.uriOrCurie shouldBe Uri("urn:id")
      ref.identifierView.typeView.uriOrCurie shouldBe Curie("xsd:string")
      ref.identifierView.subjectType shouldBe base
    }

    "provide TypeAttributeView for types" in {
      val t = c1.attributeViews("type")
        .shouldBeA[TypeAttributeView]
      t.slotView.uriOrCurie shouldBe Uri("urn:type")
      t.typeView.uriOrCurie shouldBe Curie("xsd:string")
      t.subjectType shouldBe base
    }

    "provide EnumAttributeViews for enums" in {
      val t = c1.attributeViews("enum")
        .shouldBeA[EnumAttributeView]
      t.slotView.uriOrCurie shouldBe Uri("urn:enum")
      t.enumView.uriOrCurie shouldBe Uri("urn:E1")
    }

    val upgradeSubjectTypeSchema =
      """id: urn:test
        |name: test
        |
        |imports:
        |  - linkml:types
        |
        |prefixes:
        |  ex: http://example.org/
        |  exx: http://example.org/example#
        |
        |classes:
        |  C3:
        |    attributes:
        |      slot_only:
        |        range: string
        |        implicit_prefix: ex
        |      slot_and_type:
        |        range: pfx
        |        implicit_prefix: ex
        |      type_only:
        |        range: pfx
        |      united:
        |        range: pfx
        |        equals_string: blep
        |types:
        |  pfx:
        |    implicit_prefix: exx
        |    base: str
        |    equals_string: blip
        |""".stripMargin

    lazy val sv2 = SchemaView.loadSchemaViewFromString(upgradeSubjectTypeSchema)
    lazy val c3 = sv2.classes("C3")

    "upgrade subject type from base to implicit prefix" in {
      c3.attributeViews("slot_only")
        .shouldBeA[TypeAttributeView]
        .subjectType shouldBe implicitPrefix("http://example.org/")
    }

    "prefer slot implicit prefix subject type over type implicit prefix" in {
      c3.attributeViews("slot_and_type")
        .shouldBeA[TypeAttributeView]
        .subjectType shouldBe implicitPrefix("http://example.org/")
    }

    "use type subject type implicit prefix" in {
      c3.attributeViews("type_only")
        .shouldBeA[TypeAttributeView]
        .subjectType shouldBe implicitPrefix("http://example.org/example#")
    }

    "use slot metaslots over type metaslots" in {
      c3.attributeViews("united")
        .shouldBeA[TypeAttributeView]
        .equalsString shouldBe Some("blep")
    }
  }
}

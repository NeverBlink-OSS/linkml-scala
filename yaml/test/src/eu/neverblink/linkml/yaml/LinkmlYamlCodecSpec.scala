package eu.neverblink.linkml.yaml

import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.virtuslab.yaml.*
import scala.annotation.nowarn

class LinkmlYamlCodecSpec extends AnyWordSpec, Matchers, ScalaCheckPropertyChecks {
  "LinkmlYamlCodec" should {
    "decode and encode strings" in {
      implicit val codec: LinkmlYamlCodec[String] = LinkmlYamlCodec.derived
      roundTrip("abc", "abc\n")
      roundTrip("true", "true\n")
      roundTrip("false", "false\n")
      roundTrip("123", "123\n")
      roundTrip("123.456", "123.456\n")
      roundTrip("Привіт", "Привіт\n")
      roundTrip("★🎸🎧⋆｡°⋆", "★🎸🎧⋆｡°⋆\n")
      decodeError[String](
        "a: abc\n",
        """Expected string value at 0:0 but got:
          |a: abc
          |^""".stripMargin,
      )
      decodeError[String](
        "- abc\n",
        """Expected string value at 0:2 but got:
          |- abc
          |  ^""".stripMargin,
      )
      decodeError[String](
        "!!null\n",
        """Expected string value at 1:0 but got:
          |
          |^""".stripMargin,
      )
    }
    "decode and encode ints" in {
      implicit val codec: LinkmlYamlCodec[Int] = LinkmlYamlCodec.derived
      forAll(arbitrary[Int])(x => roundTrip(x, s"$x\n"))
      decodeError[Int](
        "9876543210\n",
        """Expected 32-bit signed integer number value at 0:0 but got:
          |9876543210
          |^""".stripMargin,
      )
      decodeError[Int](
        "1.23\n",
        """Expected 32-bit signed integer number value at 0:0 but got:
          |1.23
          |^""".stripMargin,
      )
      decodeError[Int](
        "a: 123\n",
        """Expected 32-bit signed integer number value at 0:0 but got:
          |a: 123
          |^""".stripMargin,
      )
      decodeError[Int](
        "- 123\n",
        """Expected 32-bit signed integer number value at 0:2 but got:
          |- 123
          |  ^""".stripMargin,
      )
      decodeError[Int](
        "Null\n",
        """Expected 32-bit signed integer number value at 0:0 but got:
          |Null
          |^""".stripMargin,
      )
    }
    "decode and encode booleans" in {
      implicit val codec: LinkmlYamlCodec[Boolean] = LinkmlYamlCodec.derived
      forAll(arbitrary[Boolean])(x => roundTrip(x, s"$x\n"))
      decodeError[Boolean](
        "123\n",
        """Expected boolean value at 0:0 but got:
          |123
          |^""".stripMargin,
      )
      decodeError[Boolean](
        "tRUE\n",
        """Expected boolean value at 0:0 but got:
          |tRUE
          |^""".stripMargin,
      )
      decodeError[Boolean](
        "a: true\n",
        """Expected boolean value at 0:0 but got:
          |a: true
          |^""".stripMargin,
      )
      decodeError[Boolean](
        "- true\n",
        """Expected boolean value at 0:2 but got:
          |- true
          |  ^""".stripMargin,
      )
      decodeError[Boolean](
        "~\n",
        """Expected boolean value at 0:0 but got:
          |~
          |^""".stripMargin,
      )
    }
    "decode and encode options of booleans" in {
      implicit val codec: LinkmlYamlCodec[Option[Boolean]] = LinkmlYamlCodec.derived
      roundTrip[Option[Boolean]](Some(true), "true\n")
      roundTrip[Option[Boolean]](Some(false), "false\n")
      roundTrip[Option[Boolean]](None, "!!null\n")
      decodeError[Option[Boolean]](
        "123\n",
        """Expected boolean value at 0:0 but got:
          |123
          |^""".stripMargin,
      )
      decodeError[Option[Boolean]](
        "a: NULL\n",
        """Expected boolean value at 0:0 but got:
          |a: NULL
          |^""".stripMargin,
      )
      decodeError[Option[Boolean]](
        "- null\n",
        """Expected boolean value at 0:2 but got:
          |- null
          |  ^""".stripMargin,
      )
    }
    "decode and encode sequences of booleans" in {
      implicit val codec: LinkmlYamlCodec[Seq[Boolean]] = LinkmlYamlCodec.derived
      roundTrip(Seq(true, false), "- true\n- false\n")
      decodeError[Seq[Boolean]](
        "123\n",
        """Expected boolean value at 0:0 but got:
          |123
          |^""".stripMargin,
      )
      decodeError[Seq[Boolean]](
        "- ~\n",
        """Expected boolean value at 0:2 but got:
          |- ~
          |  ^""".stripMargin,
      )
      decodeError[Seq[Boolean]](
        "a: true\n",
        """Expected sequence or null value at 0:0 but got:
          |a: true
          |^""".stripMargin,
      )
    }
    "decode and encode sequences of option of booleans" in {
      implicit val codec: LinkmlYamlCodec[Seq[Option[Boolean]]] = LinkmlYamlCodec.derived
      roundTrip(Seq(Some(true), Some(false), None), "- true\n- false\n- !!null\n")
      decodeError[Seq[Option[Boolean]]](
        "123\n",
        """Expected boolean value at 0:0 but got:
          |123
          |^""".stripMargin,
      )
      decodeError[Seq[Option[Boolean]]](
        "a: null\n",
        """Expected sequence or null value at 0:0 but got:
          |a: null
          |^""".stripMargin,
      )
    }
    "decode and encode maps of strings to booleans" in {
      implicit val codec: LinkmlYamlCodec[Map[String, Boolean]] = LinkmlYamlCodec.derived
      roundTrip(Map("abc" -> true), "abc: true\n")
      decodeError[Map[String, Boolean]](
        "123\n",
        """Expected map or null value at 0:0 but got:
          |123
          |^""".stripMargin,
      )
      decodeError[Map[String, Boolean]](
        "null: true\n",
        """Expected string value at 0:0 but got:
          |null: true
          |^""".stripMargin,
      )
      decodeError[Map[String, Boolean]](
        "abc: ~\n",
        """Expected boolean value at 0:5 but got:
          |abc: ~
          |     ^""".stripMargin,
      )
    }
    "decode and encode non-abstract classes" in {
      class MyClass(val a: String, val b: Int, val c: Boolean) derives LinkmlYamlCodec {
        @nowarn override def equals(other: Any): Boolean = other match {
          case that: MyClass => a == that.a && b == that.b && c == that.c
          case _ => false
        }

        override def hashCode(): Int = 31 * (31 * a.hashCode + b.hashCode) + c.hashCode
      }

      roundTrip(new MyClass("ABC", 123, true), "a: ABC\nb: 123\nc: true\n")
      decodeError[MyClass](
        "a: ABC\nb: 123\n",
        """Expected required field 'c' of 'MyClass' at 1:0 but got:
          |b: 123
          |^""".stripMargin,
      )
    }
    "decode and encode case classes" in {
      case class MyClass(a: String, b: Int, c: Boolean) derives LinkmlYamlCodec

      roundTrip(MyClass("ABC", 123, true), "a: ABC\nb: 123\nc: true\n")
      decodeError[MyClass](
        "b: 123\nc: true\n",
        """Expected required field 'a' of 'MyClass' at 1:0 but got:
          |c: true
          |^""".stripMargin,
      )
    }
    "decode and encode case classes with private constructor" in {
      case class MyClass private (a: String, b: Int, c: Boolean) derives LinkmlYamlCodec

      object MyClass {
        def make(a: String, b: Int, c: Boolean): MyClass = new MyClass(a, b, c)
      }

      roundTrip(MyClass.make("ABC", 123, true), "a: ABC\nb: 123\nc: true\n")
      decodeError[MyClass](
        "null",
        """Expected required field 'a' of 'MyClass' at 0:0 but got:
          |null
          |^""".stripMargin,
      )
    }
    "decode and encode case classes with renamed fields" in {
      case class MyClass(@named("x") a: String, @named("y") b: Int, @named("z") c: Boolean)
          derives LinkmlYamlCodec

      roundTrip(MyClass("ABC", 123, true), "x: ABC\ny: 123\nz: true\n")
      decodeError[MyClass](
        "a: ABC\nb: 123\nc: true\n",
        """Expected required field 'x' of 'MyClass' at 2:0 but got:
          |c: true
          |^""".stripMargin,
      )
    }
    "decode and encode case classes with default values" in {
      case class MyClass(
          a: Option[String] = None,
          b: Seq[Int] = Nil,
          c: Map[String, Boolean] = Map.empty,
          d: Boolean = false,
      ) derives LinkmlYamlCodec

      roundTrip(
        MyClass(Some("ABC"), Seq(123, 456), Map("+++" -> true), true),
        "a: ABC\nb: \n  - 123\n  - 456\nc: \n  +++: true\nd: true\n",
      )
      roundTrip(MyClass(Some("ABC")), "a: ABC\n")
      decodeError[MyClass](
        "c: true\n",
        """Expected map or null value at 0:3 but got:
          |c: true
          |   ^""".stripMargin,
      )
    }
    "decode and encode recursive case classes" in {
      case class MyClass(v: Int, n: Option[MyClass] = None) derives LinkmlYamlCodec

      roundTrip(
        MyClass(1, Some(MyClass(2, Some(MyClass(3, None))))),
        "v: 1\nn: \n  v: 2\n  n: \n    v: 3\n",
      )
      decodeError[MyClass](
        "v: 1\nn: \n  v: 2\n  n: \n    x: 3\n",
        """Expected required field 'v' of 'MyClass' at 4:4 but got:
          |    x: 3
          |    ^""".stripMargin,
      )
    }
    "decode and encode case classes with simple dictionaries" in {
      case class SimpleDictEntry(
          @value v: Int,
          @id k: String,
      ) // no need to derive LinkmlYamlCodec directly

      case class MyClass(@simpleDict d: Map[String, SimpleDictEntry], x: Option[Int] = None)
          derives LinkmlYamlCodec

      roundTrip(
        MyClass(
          Map(
            "a" -> SimpleDictEntry(k = "a", v = 1),
            "b" -> SimpleDictEntry(k = "b", v = 2),
            "c" -> SimpleDictEntry(k = "c", v = 3),
          ),
        ),
        "d: \n  a: 1\n  b: 2\n  c: 3\n",
      )
      decodeError[MyClass](
        "d: \n  a: 1\n  b: 2\n  null: 3\n",
        """Expected string value at 3:2 but got:
          |  null: 3
          |  ^""".stripMargin,
      )
      decodeError[MyClass](
        "d: \n  a: 1\n  b: 2\n  c:\n",
        """Expected 32-bit signed integer number value at 4:0 but got:
          |
          |^""".stripMargin,
      )
    }
    "decode and encode case classes with compact dictionaries" in {
      case class DictEntry(@id k: String, v: Int, e: Boolean)

      case class MyClass(@compactDict d: Map[String, DictEntry], x: Option[Int] = None)
          derives LinkmlYamlCodec

      roundTrip(
        MyClass(
          Map(
            "a" -> DictEntry("a", 1, true),
            "b" -> DictEntry("b", 2, true),
            "c" -> DictEntry("c", 3, false),
          ),
        ),
        "d: \n  a: \n    v: 1\n    e: true\n  b: \n    v: 2\n    e: true\n  c: \n    v: 3\n    e: false\n",
      )
      decodeError[MyClass](
        "d: \n  a: \n    e: true\n",
        """Expected required field 'v' of 'DictEntry' at 2:4 but got:
          |    e: true
          |    ^""".stripMargin,
      )
    }
    "decode and encode case classes with expanded dictionaries" in {
      case class DictEntry(@id k: String)(
          val v: Int,
          val e: Boolean,
      ) // equals, hashCode, and toString will be generated with usage of 'k' field only

      case class MyClass(@expandedDict d: Map[String, DictEntry], x: Option[Int] = None)
          derives LinkmlYamlCodec

      roundTrip(
        MyClass(
          Map(
            "a" -> DictEntry("a")(1, true),
            "b" -> DictEntry("b")(2, true),
            "c" -> DictEntry("c")(3, false),
          ),
        ),
        "d: \n  a: \n    k: a\n    v: 1\n    e: true\n  b: \n    k: b\n    v: 2\n    e: true\n  c: \n    k: c\n    v: 3\n    e: false\n",
      )
      decodeError[MyClass](
        "d: \n  a: \n    e: true\n",
        """Expected required field 'v' of 'DictEntry' at 2:4 but got:
          |    e: true
          |    ^""".stripMargin,
      )
    }

    "decode and encode ADTs with objects for all cases" in {
      sealed trait ADT

      object ADT {
        case object Case1 extends ADT
        case object Case2 extends ADT
        case object Case3 extends ADT
      }

      implicit val codec: LinkmlYamlCodec[ADT] = LinkmlYamlCodec.derived

      roundTrip(ADT.Case1: ADT, "Case1\n")
      roundTrip(ADT.Case2: ADT, "Case2\n")
      roundTrip(ADT.Case3: ADT, "Case3\n")
      decodeError[ADT](
        "Case4\n",
        """Expected enumeration string value at 0:0 but got:
          |Case4
          |^""".stripMargin,
      )
    }

    "decode and encode Scala 3 enums" in {
      enum Enum {
        case Case1, Case2, Case3
      }

      implicit val codec: LinkmlYamlCodec[Enum] = LinkmlYamlCodec.derived

      roundTrip(Enum.Case1: Enum, "Case1\n")
      roundTrip(Enum.Case2: Enum, "Case2\n")
      roundTrip(Enum.Case3: Enum, "Case3\n")
      decodeError[Enum](
        "Case4\n",
        """Expected enumeration string value at 0:0 but got:
          |Case4
          |^""".stripMargin,
      )
    }

    "decode and encode case classes with a custom codec for booleans" in {
      implicit val codec: LinkmlYamlCodec[Boolean] = new LinkmlYamlCodec[Boolean] {
        private val trueNode = Node.ScalarNode("1")
        private val falseNode = Node.ScalarNode("0")

        override def decode(node: Node, id: Option[Any]): Boolean = node match {
          case Node.ScalarNode(value, _) => value == "true" || value == "1"
          case _ => false
        }

        override def encode(x: Boolean, skipId: Boolean): Node =
          if (x) trueNode
          else falseNode
      }

      case class MyClass(a: String, b: Int, c: Boolean) derives LinkmlYamlCodec

      roundTrip(MyClass("ABC", 123, true), "a: ABC\nb: 123\nc: 1\n")
      roundTrip(MyClass("ABC", 123, false), "a: ABC\nb: 123\nc: 0\n")
      decodeError[MyClass](
        "a: ABC\nb: 123\n",
        """Expected required field 'c' of 'MyClass' at 1:0 but got:
          |b: 123
          |^""".stripMargin,
      )
    }
    "decode and encode generic case classes with one field as scalar nodes" in {
      case class MyClass[A](v: A)

      implicit val codec: LinkmlYamlCodec[MyClass[Int]] = LinkmlYamlCodec.derived
      roundTrip(MyClass(1), "1\n")
      decodeError[MyClass[Int]](
        "true\n",
        """Expected 32-bit signed integer number value at 0:0 but got:
          |true
          |^""".stripMargin,
      )
      decodeError[MyClass[Int]](
        "v: 1\n",
        """Expected 32-bit signed integer number value at 0:0 but got:
          |v: 1
          |^""".stripMargin,
      )
    }
    "decode and encode case classes generic case classes using implicit values for types used in fields" in {
      case class MyClass[A, B, C](a: A, b: B, c: C) derives LinkmlYamlCodec

      implicit val codec1: LinkmlYamlCodec[String] = LinkmlYamlCodec.derived
      implicit val codec2: LinkmlYamlCodec[Int] = LinkmlYamlCodec.derived
      implicit val codec3: LinkmlYamlCodec[Boolean] = LinkmlYamlCodec.derived
      roundTrip(MyClass("ABC", 123, true), "a: ABC\nb: 123\nc: true\n")
      roundTrip(MyClass(123, "ABC", true), "a: 123\nb: ABC\nc: true\n")
      roundTrip(MyClass(123, true, "ABC"), "a: 123\nb: true\nc: ABC\n")
      roundTrip(MyClass("ABC", "DEF", "GHI"), "a: ABC\nb: DEF\nc: GHI\n")
      decodeError[MyClass[Int, Int, Int]](
        "a: ~\nb: ~\nc: ~\n",
        """Expected 32-bit signed integer number value at 0:3 but got:
          |a: ~
          |   ^""".stripMargin,
      )
    }

    "decode simpleDict as a compactDict (fallback) " in {
      case class Annotable(
          @simpleDict
          annotations: Map[String, Annotation],
          x: Option[String] = None,
      )
      case class Annotation(
          @id
          tag: String,
          @value
          value: String,
          additionalValue: Option[String] = None,
      )
      val yaml =
        """annotations:
          |  someTag:
          |    # compact dict with optional value set
          |    value: someValue
          |    additionalValue: someAdditionalValue
          |  someOtherTag:
          |    # expanded dict with optional value unset
          |    tag: someOtherTag
          |    value: someOtherValue
          |""".stripMargin

      val codec = LinkmlYamlCodec.derived[Annotable]

      parseYaml(yaml).map(x => codec.decode(x)) shouldEqual Right(
        Annotable(
          Map(
            "someTag" -> Annotation("someTag", "someValue", Some("someAdditionalValue")),
            "someOtherTag" -> Annotation("someOtherTag", "someOtherValue"),
          ),
        ),
      )
    }

    "decode nested mixed simple/compact Dicts" in {
      case class Annotable(
          @simpleDict
          annotations: Map[String, Annotation],
          x: Option[String] = None,
      )
      case class Annotation(
          @id
          tag: String,
          @value
          value: String,
          @simpleDict
          annotations: Map[String, Annotation] = Map(),
      )
      val yaml =
        """annotations:
          |  tooltip:
          |    tag: tooltip
          |    value: tooltip value
          |    annotations:
          |      source: source value
          |""".stripMargin

      val codec = LinkmlYamlCodec.derived[Annotable]

      parseYaml(yaml).map(x => codec.decode(x)) shouldEqual Right(
        Annotable(
          Map(
            "tooltip" -> Annotation(
              "tooltip",
              "tooltip value",
              Map("source" -> Annotation("source", "source value")),
            ),
          ),
        ),
      )
    }

    "don't generate codecs for classes with private fields in the primary constructor" in {
      assert(intercept[TestFailedException](assertCompiles {
        """class MyClass(a: String, b: Int, c: Boolean) derives LinkmlYamlCodec"""
      }).getMessage.contains {
        """Getter or field 'a' of 'MyClass' is private. It should be defined as 'val' or 'var' in the primary constructor."""
      })
      assert(intercept[TestFailedException](assertCompiles {
        """case class MyClass(private[this] val a: String, b: Int, c: Boolean) derives LinkmlYamlCodec"""
      }).getMessage.contains {
        """Getter or field 'a' of 'MyClass' is private. It should be defined as 'val' or 'var' in the primary constructor."""
      })
    }
    "don't generate codecs for unsupported types" in {
      assert(intercept[TestFailedException](assertCompiles {
        """LinkmlYamlCodec.derived[Set[Int]]"""
      }).getMessage.contains {
        """Cannot find leaf objects for ADT base 'scala.collection.immutable.Set[scala.Int]'. Please add them or provide a custom implicitly accessible codec for the ADT base."""
      })
      assert(intercept[TestFailedException](assertCompiles {
        """LinkmlYamlCodec.derived[Map[Float, String]]"""
      }).getMessage.contains {
        """Cannot find leaf objects for ADT base 'scala.Float'. Please add them or provide a custom implicitly accessible codec for the ADT base."""
      })
    }
    "don't generate codecs for case classes with unexpected combination of field annotations" in {
      assert(intercept[TestFailedException](assertCompiles {
        """case class MyClass(@id @value a: String, b: Int, @named("x") c: Boolean) derives LinkmlYamlCodec"""
      }).getMessage.contains {
        """Expected only one of annotation: '@id', '@value', '@simpleDict', '@compactDict', or '@expandedDict' for 'a' of 'MyClass'."""
      })
      assert(intercept[TestFailedException](assertCompiles {
        """case class MyClass(@id @named("x") a: String, @value @named("y") b: Int, @named("z") @named("zed") c: Boolean) derives LinkmlYamlCodec"""
      }).getMessage.contains {
        """Duplicated 'eu.neverblink.linkml.runtime.named' defined for 'c' of 'MyClass'."""
      })
      assert(intercept[TestFailedException](assertCompiles {
        """case class MyClass(@named("b") a: String, b: Int, c: Boolean) derives LinkmlYamlCodec"""
      }).getMessage.contains {
        """Duplicated yaml key(s) defined for 'MyClass': 'b'. Keys are derived from field names of the class and can be overridden by 'eu.neverblink.linkml.runtime.named' annotation(s)."""
      })
      assert(intercept[TestFailedException](assertCompiles {
        """case class MyClass(@named("x" + "y") a: String, b: Int, c: Boolean) derives LinkmlYamlCodec"""
      }).getMessage.contains {
        """Cannot evaluate a parameter of the '@named' annotation in type 'MyClass'"""
      })
      assert(intercept[TestFailedException](assertCompiles {
        """case class MyClass(@id a: String, @id b: Int, c: Boolean) derives LinkmlYamlCodec"""
      }).getMessage.contains {
        """More than one field is defined with '@id' annotation in 'MyClass'."""
      })
      assert(intercept[TestFailedException](assertCompiles {
        """case class MyClass(@id a: String, @value b: Int, @value c: Boolean) derives LinkmlYamlCodec"""
      }).getMessage.contains {
        """More than one field is defined with '@value' annotation in 'MyClass'."""
      })
    }
  }

  private def roundTrip[T](value: T, yaml: String)(implicit codec: LinkmlYamlCodec[T]): Unit = {
    parseYaml(yaml).map(x => codec.decode(x)) shouldEqual Right(value)
    codec.encode(value).asYaml shouldEqual yaml
  }

  private def decodeError[T](yaml: String, error: String)(implicit
      codec: LinkmlYamlCodec[T],
  ): Unit =
    assert(
      intercept[Throwable](parseYaml(yaml).map(x => codec.decode(x))).getMessage.contains(error),
    )
}

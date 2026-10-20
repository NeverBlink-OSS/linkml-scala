package eu.neverblink.linkml.runtime

import scala.annotation.{StaticAnnotation, unused}
import scala.annotation.meta.field

@field final class named(name: String) extends StaticAnnotation

@field final class id extends StaticAnnotation

@field final class value extends StaticAnnotation

@field final class simpleDict extends StaticAnnotation

@field final class compactDict extends StaticAnnotation

@field final class expandedDict extends StaticAnnotation

/** Marks a field whose default value is meaningful and must always be serialized, even when the
  * field is set to the default value. Used for defaults derived from the LinkML `ifabsent`
  * metaslot.
  *
  * Only valid on fields that actually declare a default value.
  */
@field final class serializeDefault extends StaticAnnotation

/** Marks a field that holds the prefixes of a document, such as the `prefixes` of a LinkML schema.
  *
  * The field is a `Map` or a `Seq` of objects. In each object, the field named [[prefixField]]
  * holds a prefix, and the field named [[namespaceField]] holds its namespace. Codecs read this
  * field before the other fields of the class, and use its prefixes for everything in the object,
  * as described in [[PrefixMap]]. The field itself is decoded and encoded without prefixes.
  *
  * Inside a nested object with such a field, the nested prefixes replace the outer document's
  * prefixes. They are not added to them, so a prefix that only the outer document declares is
  * unknown inside the nested object.
  *
  * The Scala generator adds it to a slot that is multivalued and inlined, when the range class has
  * a slot with the `sh:prefix` URI and a slot with the `sh:namespace` URI, as suggested in
  * https://github.com/linkml/linkml/issues/3906.
  *
  * {{{
  * final case class Prefix(@id prefix: String, @value namespace: Uri)
  *
  * final case class Dataset(
  *     @documentPrefixes("prefix", "namespace") @simpleDict prefixes: Map[String, Prefix] = Map(),
  *     items: Seq[UriOrCurie] = Nil,
  * )
  * }}}
  */
@field final class documentPrefixes(@unused prefixField: String, @unused namespaceField: String)
    extends StaticAnnotation

/** Marks a class with exactly one field that is serialized as the value of that field, instead of
  * as an object holding it. For example, `Uri("http://x")` is written as `"http://x"` rather than
  * `{original: "http://x"}`.
  *
  * Classes with one field and no such annotation are serialized as normal objects.
  */
final class flatten extends StaticAnnotation

/** Marks a class whose instances may have data beyond its fields, which decoding then ignores.
  * Without it, decoding fails on such data, as LinkML does by default. Generated for classes whose
  * `extra_slots` metaslot allows extra data.
  *
  * As `extra_slots` is not inherited in LinkML, neither is this annotation: a subclass of a class
  * that allows extra data forbids it, unless its own `extra_slots` allows it too.
  */
final class extraSlotsAllowed extends StaticAnnotation

/** Lists the implementations of a class that has a type designator slot.
  *
  * In LinkML, a slot with `designates_type: true` holds the name of an object's class. Generated
  * class hierarchies are not sealed, so macros cannot find the implementations of a class on their
  * own. This annotation lists them. Macros that derive codecs use the list to pick the
  * implementation.
  *
  * The Scala generator adds it to a class when a slot can hold more than one implementation of the
  * class.
  *
  * {{{
  * @typeDesignator(
  *   "shape_type",
  *   typeDesignator.Member(classOf[Circle], "Circle"),
  *   typeDesignator.Member(classOf[RectangleImpl], "Rectangle"),
  * )
  * abstract class Shape
  * }}}
  *
  * @param slot
  *   Name of the type designator slot, as serialized.
  * @param members
  *   The implementations and the designator values that select them. Encoding checks them in this
  *   order.
  * @see
  *   https://linkml.io/linkml/schemas/type-designators.html
  */
final class typeDesignator(slot: String, members: typeDesignator.Member*) extends StaticAnnotation

object typeDesignator {

  /** One implementation of a class marked with [[typeDesignator]], with the designator values that
    * select it:
    *
    * {{{
    * typeDesignator.Member(classOf[RectangleImpl], "Rectangle")                // string
    * typeDesignator.Member(classOf[Circle], Uri("https://example.org/Circle")) // uri
    * typeDesignator.Member(classOf[Circle], Curie("ex:Circle"))                // curie
    * typeDesignator.Member(                                                     // uriorcurie
    *   classOf[Circle],
    *   Uri("https://example.org/Circle"),
    *   Curie("ex:Circle"),
    * )
    * }}}
    *
    * Macros read the arguments as literals, so they must be written out as above.
    *
    * @param cls
    *   The implementation. It must be a concrete subclass of the annotated class.
    * @param values
    *   The designator values that select the implementation.
    */
  final class Member private (@unused cls: Class[?], @unused values: List[String]) {

    /** @param name
      *   The LinkML class name, for a `string` designator.
      */
    def this(cls: Class[?], name: String) = this(cls, name :: Nil)

    /** @param value
      *   The class URI for a `uri` designator, or the class CURIE for a `curie` designator.
      */
    def this(cls: Class[?], value: UriOrCurie) = this(cls, value.original :: Nil)

    /** For a `uriorcurie` designator. Data can hold either value.
      *
      * @param uri
      *   The class URI.
      * @param curie
      *   The class CURIE.
      */
    def this(cls: Class[?], uri: Uri, curie: Curie) =
      this(cls, uri.original :: curie.original :: Nil)
  }
}

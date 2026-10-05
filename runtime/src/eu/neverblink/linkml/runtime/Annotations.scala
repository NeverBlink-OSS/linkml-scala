package eu.neverblink.linkml.runtime

import scala.annotation.StaticAnnotation
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

/** Marks a class with exactly one field that is serialized as the value of that field, instead of
  * as an object holding it. For example, `Uri("http://x")` is written as `"http://x"` rather than
  * `{original: "http://x"}`.
  *
  * Classes with one field and no such annotation are serialized as normal objects.
  */
final class flatten extends StaticAnnotation

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

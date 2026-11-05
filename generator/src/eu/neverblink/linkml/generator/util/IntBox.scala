package eu.neverblink.linkml.generator.util

/** An `Int` that can be changed in place, such as a count or a set of flags in a map, so the map
  * holds no boxed `Integer`s that are replaced on each change.
  */
final class IntBox(var value: Int = 0)

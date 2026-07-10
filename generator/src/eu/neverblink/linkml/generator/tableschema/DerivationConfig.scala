package eu.neverblink.linkml.generator.tableschema

import io.circe.derivation.Configuration

// This gets used for all codec derivations within the `tableschema` package
given Configuration = Configuration.default.withDefaults

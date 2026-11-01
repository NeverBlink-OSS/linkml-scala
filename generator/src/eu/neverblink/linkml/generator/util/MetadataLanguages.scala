package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.generator.options.MetadataLanguageOptions
import eu.neverblink.linkml.runtime.MetadataLanguage

/** The language from the options in context. */
given metadataLanguage(using options: MetadataLanguageOptions): MetadataLanguage =
  MetadataLanguage(options.metadataLanguage)

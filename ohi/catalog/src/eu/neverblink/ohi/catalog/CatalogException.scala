package eu.neverblink.ohi.catalog

import eu.neverblink.ohi.model.generated.error.CatalogError

final class CatalogException(val error: CatalogError, cause: Option[Throwable] = None)
    extends RuntimeException(error.message, cause.orNull)

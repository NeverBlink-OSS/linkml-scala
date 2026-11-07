package eu.neverblink.ohi.sdk;

/** A failure of a {@link CatalogClient} operation, with its details in {@link #getError()}. */
public class CatalogException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient CatalogError error;

    public CatalogException(CatalogError error) {
        this(error, null);
    }

    public CatalogException(CatalogError error, Throwable cause) {
        super(error.getMessage(), cause);
        this.error = error;
    }

    public CatalogError getError() {
        return error;
    }
}

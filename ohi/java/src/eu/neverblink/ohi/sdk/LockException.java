package eu.neverblink.ohi.sdk;

/** A failure of a {@link Locks} operation, with its details in {@link #getError()}. */
public class LockException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient LockError error;

    public LockException(LockError error) {
        this(error, null);
    }

    public LockException(LockError error, Throwable cause) {
        super(error.getMessage(), cause);
        this.error = error;
    }

    public LockError getError() {
        return error;
    }
}

package eu.neverblink.linkml.nativelib;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

/**
 * An {@link OutputStream} whose target is one block of unmanaged memory, grown with realloc as it
 * fills up. This lets us efficiently write into memory that we pass on to native consumers via the
 * C ABI.
 *
 * <p>The block belongs to the stream until {@link #take()} hands it to the caller. {@link
 * #discard()} frees whatever is still owned and is safe to call twice, so a failure path can always
 * run it without knowing how far the generator got.
 *
 * <p>Only works inside Graal Native Image, because {@link UnmanagedMemory} needs the image's malloc.
 * Not thread-safe: one of these serves one call.
 */
final class UnmanagedOutputStream extends OutputStream {

    /** Matches jsoniter's buffer size, so a document under 32 KB never has to grow. */
    private static final int INITIAL = 32 * 1024;
    /** Past this size, grow by half rather than doubling. */
    private static final int HALF_GROWTH_FROM = 8 * 1024 * 1024;
    private static final int MAX = Integer.MAX_VALUE - 8;

    /**
     * The block, as a raw address. Deliberately a plain long rather than a CCharPointer: keeping
     * word types out of fields sidesteps native-image's restrictions on where they may appear.
     */
    private long address;

    private int capacity;
    private int length;

    /** A view of the block, for bulk copies. Rebuilt whenever realloc moves the block. */
    private ByteBuffer view;

    UnmanagedOutputStream() {
        // malloc throws OutOfMemoryError rather than returning NULL.
        CCharPointer buffer = UnmanagedMemory.malloc(INITIAL);
        this.address = buffer.rawValue();
        this.capacity = INITIAL;
        this.view = CTypeConversion.asByteBuffer(buffer, INITIAL);
    }

    /** Bytes written so far, not counting the terminator. */
    int length() {
        return length;
    }

    @Override
    public void write(int b) {
        if (length == capacity) {
            grow(length + 1);
        }
        pointer().write(length, (byte) b);
        length++;
    }

    @Override
    public void write(byte[] bytes, int offset, int count) {
        Objects.checkFromIndexSize(offset, count, bytes.length);
        if (capacity - length < count) {
            grow(length + count);
        }
        view.limit(capacity).position(length);
        view.put(bytes, offset, count);
        length += count;
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    /**
     * NUL-terminate the document and hand the block over.
     * Releases ownership of the block to the caller.
     */
    CCharPointer take() {
        if (length == capacity) {
            grow(length + 1);
        }
        CCharPointer buffer = pointer();
        buffer.write(length, (byte) 0);
        release();
        return buffer;
    }

    /** Free the block if it is still ours. Safe to call twice, and safe after {@link #take()}. */
    void discard() {
        if (address != 0L) {
            CCharPointer buffer = pointer();
            release();
            UnmanagedMemory.free(buffer);
        }
    }

    /**
     * Make room for {@code needed} bytes in total.
     *
     * <p>realloc throws {@link OutOfMemoryError} rather than returning NULL, and leaves the old
     * block untouched when it does, so the stream still owns something for {@link #discard()} to
     * free.
     */
    private void grow(int needed) {
        if (needed < 0 || needed > MAX) {
            throw new OutOfMemoryError("the generated document does not fit in " + MAX + " bytes");
        }
        int size = capacity;
        while (size < needed) {
            int step = size < HALF_GROWTH_FROM ? size : size / 2;
            size = size > MAX - step ? MAX : size + step;
        }
        CCharPointer moved = UnmanagedMemory.realloc(pointer(), WordFactory.unsigned(size));
        address = moved.rawValue();
        capacity = size;
        view = CTypeConversion.asByteBuffer(moved, size);
    }

    /**
     * Forget the block without freeing it.
     *
     * <p>Dropping {@link #view} matters: caller may free this memory after take(), so dereferencing
     * this pointer would be use-after-free.
     */
    private void release() {
        address = 0L;
        capacity = 0;
        length = 0;
        view = null;
    }

    private CCharPointer pointer() {
        return WordFactory.pointer(address);
    }
}

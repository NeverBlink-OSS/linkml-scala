package eu.neverblink.linkml.nativelib;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CConst;
import org.graalvm.nativeimage.c.type.CTypeConversion;

/**
 * The C ABI of the shared library: two functions, both of which GraalVM turns into exported
 * symbols.
 *
 * <p>{@code linkml_call} takes a JSON request and returns a JSON response, both NUL-terminated
 * UTF-8. See {@link LinkMlNativeApi} for the protocol. {@code linkml_free} releases a response.
 *
 * <p>This is Java rather than Scala because {@code @CEntryPoint} methods have to be static, and
 * writing them here is more direct than relying on the static forwarders the Scala compiler
 * generates for an {@code object}.
 *
 * <p>Every entry point also has to catch {@link Throwable}: an exception escaping into C aborts the
 * whole process, which for a library loaded into someone else's Python interpreter is unacceptable.
 */
public final class LinkMlCApi {

    private LinkMlCApi() {}

    /**
     * Run one request.
     *
     * @param thread the calling thread's isolate thread, from {@code graal_create_isolate} or
     *     {@code graal_attach_thread}
     * @param request a NUL-terminated UTF-8 JSON request
     * @return a NUL-terminated UTF-8 JSON response, which the caller must release with {@code
     *     linkml_free}
     */
    @CEntryPoint(name = "linkml_call")
    static CCharPointer call(IsolateThread thread, @CConst CCharPointer request) {
        String response;
        try {
            response = LinkMlNativeApi.call(CTypeConversion.toJavaString(request));
        } catch (Throwable outer) {
            // LinkMlNativeApi.call() handles everything non-fatal itself, so getting here means
            // something like a StackOverflowError -- which may well break the reply too.
            try {
                response = LinkMlNativeApi.fatalResponse(outer);
            } catch (Throwable ignored) {
                response = "{\"ok\":false,\"error\":\"unrecoverable error in the LinkML native library\"}";
            }
        }
        return toCString(response);
    }

    /**
     * Release a response returned by {@code linkml_call}. Does nothing when given NULL.
     *
     * @param thread the calling thread's isolate thread
     * @param response a pointer previously returned by {@code linkml_call}
     */
    @CEntryPoint(name = "linkml_free")
    static void free(IsolateThread thread, CCharPointer response) {
        try {
            if (response.isNonNull()) {
                UnmanagedMemory.free(response);
            }
        } catch (Throwable ignored) {
            // Freeing must not throw into C. A leak beats a crash.
        }
    }

    /**
     * Copy a string into unmanaged memory as NUL-terminated UTF-8, so that it stays valid after this
     * call returns and can be freed from C.
     *
     * TODO: direct serialize to the buffer with jsoniter?
     */
    private static CCharPointer toCString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        CCharPointer buffer = UnmanagedMemory.malloc(bytes.length + 1);
        ByteBuffer out = CTypeConversion.asByteBuffer(buffer, bytes.length + 1);
        out.put(bytes);
        out.put((byte) 0);
        return buffer;
    }
}

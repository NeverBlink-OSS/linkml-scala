package eu.neverblink.linkml.nativelib;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CConst;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

/**
 * The C ABI of the shared library.
 *
 * <p>Conventions:
 *
 * <ul>
 *   <li><b>Options are one JSON string</b>, and may be NULL for defaults. The recognized fields
 *   are documented in docs/python_bindings.md.
 *   <li><b>Failure is NULL plus a message.</b> A function that returns a string returns NULL and
 *       writes the reason to {@code *error}. Loading returns handle 0 instead. Both {@code *error}
 *       and {@code *report} are set to NULL first, so a caller can reuse them across calls.
 * </ul>
 *
 * <p>Every string the functions return -- documents, reports and error messages -- are owned by the
 * caller and must be released with {@code linkml_free}.
 */
public final class LinkMlCApi {

    private LinkMlCApi() {}

    /** The version of this ABI, so a caller can check it matches what it was built against. */
    @CEntryPoint(name = "linkml_abi_version")
    static int abiVersion(IsolateThread thread) {
        return LinkMlNativeApi.abiVersion();
    }

    /**
     * Version and build metadata for this library, as a JSON {@code BuildInfo}. Needs no schema.
     *
     * <p>Goes through {@code document} like everything else that returns a string, so the memory
     * handling stays in one place; the handle and options it takes are unused here.
     *
     * @param error receives the reason the call could not be made
     * @return JSON describing this build, or NULL on failure
     */
    @CEntryPoint(name = "linkml_build_info")
    static CCharPointer buildInfo(IsolateThread thread, CCharPointerPointer error) {
        return document(
                0L,
                WordFactory.nullPointer(),
                error,
                (handle, options, out) -> LinkMlNativeApi.buildInfo(out));
    }

    /**
     * Load a schema from the file system, resolving its imports from disk.
     *
     * @param path the schema file to read
     * @param options JSON, or NULL. Recognizes {@code inferMessages}.
     * @param report receives the validation report as JSON, whether loading succeeded
     * @param error receives the reason the call could not be made
     * @return a schema handle, or 0 if the schema could not be loaded
     */
    @CEntryPoint(name = "linkml_load_file")
    static long loadFile(
            IsolateThread thread,
            @CConst CCharPointer path,
            @CConst CCharPointer options,
            CCharPointerPointer report,
            CCharPointerPointer error) {
        clear(report);
        clear(error);
        try {
            // Converted inside the try: a bad pointer should be an error, not a crash.
            return complete(report, LinkMlNativeApi.loadFile(string(path), string(options)));
        } catch (Throwable failure) {
            report(error, failure);
            return 0L;
        }
    }

    /**
     * Load a schema from memory, resolving its imports against a caller-supplied map.
     *
     * <p>The map is two parallel string arrays.
     *
     * @param path the root schema's own key in the import map, or NULL to load {@code schema}
     *     directly. Use this to correctly resolve circular imports.
     * @param schema the root schema as YAML. Ignored when {@code path} is given.
     * @param importNames import map keys, {@code importCount} of them
     * @param importBodies the matching schema texts
     * @param options JSON, or NULL. Recognizes {@code inferMessages}.
     * @param report receives the validation report as JSON, whether loading succeeded
     * @param error receives the reason the call could not be made
     * @return a schema handle, or 0 if the schema could not be loaded
     */
    @CEntryPoint(name = "linkml_load_string")
    static long loadString(
            IsolateThread thread,
            @CConst CCharPointer path,
            @CConst CCharPointer schema,
            CCharPointerPointer importNames,
            CCharPointerPointer importBodies,
            int importCount,
            @CConst CCharPointer options,
            CCharPointerPointer report,
            CCharPointerPointer error) {
        clear(report);
        clear(error);
        try {
            return complete(
                    report,
                    LinkMlNativeApi.loadString(
                            string(path),
                            string(schema),
                            strings(importNames, importCount),
                            strings(importBodies, importCount),
                            string(options)));
        } catch (Throwable failure) {
            report(error, failure);
            return 0L;
        }
    }

    /** Release a schema handle. Closing one that is already gone does nothing. */
    @CEntryPoint(name = "linkml_close")
    static void close(IsolateThread thread, long handle) {
        try {
            LinkMlNativeApi.close(handle);
        } catch (Throwable ignored) {
            // Releasing must not throw into C.
        }
    }

    // The generator entry points are in LinkMlCGenerators, generated from
    // mill-build/src/Entrypoints.scala. They all share the `document` helper below.

    @CEntryPoint(name = "linkml_lint")
    static CCharPointer lint(
            IsolateThread thread, long handle, @CConst CCharPointer options, CCharPointerPointer error) {
        return document(handle, options, error, LinkMlNativeApi::lint);
    }

    /** Release anything this library returned. Does nothing when given NULL. */
    @CEntryPoint(name = "linkml_free")
    static void free(IsolateThread thread, CCharPointer buffer) {
        try {
            if (buffer.isNonNull()) {
                UnmanagedMemory.free(buffer);
            }
        } catch (Throwable ignored) {
            // Freeing must not throw into C. A leak beats a crash.
        }
    }

    // Plumbing

    /**
     * One of the generator entry points on {@link LinkMlNativeApi}.
     *
     * <p>None of the parameters is a word type, because native-image does not support passing those
     * into a lambda or a method reference.
     */
    @FunctionalInterface
    interface Generator {
        void generate(long handle, String optionsJson, OutputStream out);
    }

    /**
     * Run a generator, applying the NULL-plus-message convention.
     *
     * <p>The generator writes into unmanaged memory as it goes, so the document is never held on the
     * Java heap. The stream owns that memory until {@code take} hands it to the caller.
     */
    static CCharPointer document(
            long handle, @CConst CCharPointer options, CCharPointerPointer error, Generator work) {
        clear(error);
        UnmanagedOutputStream out = null;
        try {
            out = new UnmanagedOutputStream();
            work.generate(handle, string(options), out);
            return out.take();
        } catch (Throwable failure) {
            // Release the partial document before describing the failure: when the failure is
            // "I'm running out of memory", the message needs a few bytes of it back.
            if (out != null) {
                out.discard();
            }
            report(error, failure);
            return WordFactory.nullPointer();
        }
    }

    /** Write the report from `load` to its out-param and return the handle it produced. */
    private static long complete(CCharPointerPointer report, LinkMlNativeApi.Loaded loaded) {
        write(report, loaded.report());
        return loaded.handle();
    }

    /** Describe a failure into an out-param, falling back to a constant if even that fails. */
    private static void report(CCharPointerPointer error, Throwable failure) {
        String message;
        try {
            message = LinkMlNativeApi.describe(failure);
        } catch (Throwable ignored) {
            // Getting here means something like a StackOverflowError, which may break the reply too.
            message = "unrecoverable error in the LinkML native library";
        }
        try {
            write(error, message);
        } catch (Throwable ignored) {
            // Out of memory even for the message. Leaving *error NULL beats letting this escape:
            // an exception out of a @CEntryPoint aborts the process, and the callers already treat
            // "NULL with no message" as a failure.
        }
    }

    private static void clear(CCharPointerPointer out) {
        if (out.isNonNull()) {
            out.write(WordFactory.nullPointer());
        }
    }

    private static void write(CCharPointerPointer out, String value) {
        if (out.isNonNull()) {
            out.write(copy(value));
        }
    }

    /** NULL comes back as null, so an omitted optional argument stays optional in Scala. */
    private static String string(CCharPointer ptr) {
        return ptr.isNull() ? null : CTypeConversion.toJavaString(ptr);
    }

    private static String[] strings(CCharPointerPointer array, int count) {
        if (count <= 0) {
            return new String[0];
        }
        if (array.isNull()) {
            throw new IllegalArgumentException("import map has " + count + " entries but no array");
        }
        String[] values = new String[count];
        for (int i = 0; i < count; i++) {
            values[i] = string(array.read(i));
        }
        return values;
    }

    /**
     * Copy a string into unmanaged memory as NUL-terminated UTF-8, so it stays valid after the call
     * returns and can be freed from C.
     * <p>
     * Only used for the small strings: error messages and the load functions' validation report.
     * Documents go through {@link UnmanagedOutputStream} instead, which never builds a string.
     */
    private static CCharPointer copy(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        CCharPointer buffer = UnmanagedMemory.malloc(bytes.length + 1);
        ByteBuffer out = CTypeConversion.asByteBuffer(buffer, bytes.length + 1);
        out.put(bytes);
        out.put((byte) 0);
        return buffer;
    }
}

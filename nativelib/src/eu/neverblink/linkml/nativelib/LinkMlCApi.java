package eu.neverblink.linkml.nativelib;

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

    // Generators. Each returns its document, or NULL with *error set.

    /** Generate JSON Schema. Options: {@code open}, {@code treeRoot}, {@code treeRootInlineType}. */
    @CEntryPoint(name = "linkml_json_schema")
    static CCharPointer jsonSchema(
            IsolateThread thread, long handle, @CConst CCharPointer options, CCharPointerPointer error) {
        return document(handle, options, error, LinkMlNativeApi::jsonSchema);
    }

    /** Generate SHACL shapes as N-Triples. Options: {@code open}, {@code onlyClassesFromRootSchema}. */
    @CEntryPoint(name = "linkml_shacl")
    static CCharPointer shacl(
            IsolateThread thread, long handle, @CConst CCharPointer options, CCharPointerPointer error) {
        return document(handle, options, error, LinkMlNativeApi::shacl);
    }

    /** Generate RDFS as N-Triples. Options: {@code onlyClassesFromRootSchema}. */
    @CEntryPoint(name = "linkml_rdfs")
    static CCharPointer rdfs(
            IsolateThread thread, long handle, @CConst CCharPointer options, CCharPointerPointer error) {
        return document(handle, options, error, LinkMlNativeApi::rdfs);
    }

    /**
     * Materialize a derived LinkML schema. Options: {@code skipDerivation}, {@code pruningMode},
     * {@code treeRoot}, {@code format}.
     */
    @CEntryPoint(name = "linkml_linkml")
    static CCharPointer linkml(
            IsolateThread thread, long handle, @CConst CCharPointer options, CCharPointerPointer error) {
        return document(handle, options, error, LinkMlNativeApi::linkml);
    }

    /** Generate a Frictionless Table Schema as JSON. Options: {@code treeRoot}. */
    @CEntryPoint(name = "linkml_table_schema")
    static CCharPointer tableSchema(
            IsolateThread thread, long handle, @CConst CCharPointer options, CCharPointerPointer error) {
        return document(handle, options, error, LinkMlNativeApi::tableSchema);
    }

    /** Generate a GraphQL schema. Options: {@code pruningMode}, {@code treeRoot}. */
    @CEntryPoint(name = "linkml_graphql")
    static CCharPointer graphQl(
            IsolateThread thread, long handle, @CConst CCharPointer options, CCharPointerPointer error) {
        return document(handle, options, error, LinkMlNativeApi::graphQl);
    }

    /**
     * Generate a Mermaid ER diagram. Options: {@code pruningMode}, {@code treeRoot}, {@code
     * optionalMarker}.
     */
    @CEntryPoint(name = "linkml_er_diagram")
    static CCharPointer erDiagram(
            IsolateThread thread, long handle, @CConst CCharPointer options, CCharPointerPointer error) {
        return document(handle, options, error, LinkMlNativeApi::erDiagram);
    }

    /**
     * Generate Scala sources, as a JSON object mapping filename to source. JSON because this is the
     * one generator producing several files. Options: {@code package}, {@code generateEmitPrefixes}.
     */
    @CEntryPoint(name = "linkml_scala")
    static CCharPointer scalaFiles(
            IsolateThread thread, long handle, @CConst CCharPointer options, CCharPointerPointer error) {
        return document(handle, options, error, LinkMlNativeApi::scalaFiles);
    }

    /**
     * Lint a loaded schema, returning a validation report as JSON. Options: {@code inferMessages}.
     */
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

    /** One of the generator entry points on {@link LinkMlNativeApi}. */
    @FunctionalInterface
    private interface Generator {
        String generate(long handle, String optionsJson);
    }

    /**
     * Run a generator, applying the NULL-plus-message convention.
     */
    private static CCharPointer document(
            long handle, @CConst CCharPointer options, CCharPointerPointer error, Generator work) {
        clear(error);
        try {
            return copy(work.generate(handle, string(options)));
        } catch (Throwable failure) {
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
        write(error, message);
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

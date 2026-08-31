// AUTO-GENERATED from mill-build/src/Entrypoints.scala and the generators' Options case
// classes. Do not edit by hand - regenerate with ./mill bindings.
package eu.neverblink.linkml.nativelib;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CConst;

/**
 * The generator entry points of the C ABI, one per generator.
 *
 * <p>All of them have the same shape: a schema handle, an options JSON that may be NULL for
 * defaults, and an error out-param. They return the generated document, or NULL with {@code
 * *error} set. Release returned strings with {@code linkml_free}.
 *
 * <p>See {@link LinkMlCApi} for loading, linting and the lifecycle.
 */
public final class LinkMlCGenerators {

    private LinkMlCGenerators() {}

    /** Generate JSON Schema. Options: {@code open}, {@code treeRoot}, {@code treeRootInlineType}, {@code indentationStep}. */
    @CEntryPoint(name = "linkml_json_schema")
    static CCharPointer jsonSchema(
            IsolateThread thread,
            long handle,
            @CConst CCharPointer options,
            CCharPointerPointer error) {
        return LinkMlCApi.document(handle, options, error, LinkMlNativeApi::jsonSchema);
    }

    /** Generate SHACL shapes as RDF. Options: {@code open}, {@code onlyClassesFromRootSchema}, {@code format}. */
    @CEntryPoint(name = "linkml_shacl")
    static CCharPointer shacl(
            IsolateThread thread,
            long handle,
            @CConst CCharPointer options,
            CCharPointerPointer error) {
        return LinkMlCApi.document(handle, options, error, LinkMlNativeApi::shacl);
    }

    /** Generate RDFS as RDF. Options: {@code onlyClassesFromRootSchema}, {@code format}. */
    @CEntryPoint(name = "linkml_rdfs")
    static CCharPointer rdfs(
            IsolateThread thread,
            long handle,
            @CConst CCharPointer options,
            CCharPointerPointer error) {
        return LinkMlCApi.document(handle, options, error, LinkMlNativeApi::rdfs);
    }

    /** Materialize a derived LinkML schema. Options: {@code pruningMode}, {@code skipClassDerivation}, {@code outputFormat}. */
    @CEntryPoint(name = "linkml_linkml")
    static CCharPointer linkml(
            IsolateThread thread,
            long handle,
            @CConst CCharPointer options,
            CCharPointerPointer error) {
        return LinkMlCApi.document(handle, options, error, LinkMlNativeApi::linkml);
    }

    /** Generate a Frictionless Data Package, as a JSON object mapping filename to content. Options: {@code pruningMode}, {@code skipClassesWithoutIdentifier}. */
    @CEntryPoint(name = "linkml_frictionless")
    static CCharPointer frictionless(
            IsolateThread thread,
            long handle,
            @CConst CCharPointer options,
            CCharPointerPointer error) {
        return LinkMlCApi.document(handle, options, error, LinkMlNativeApi::frictionlessFiles);
    }

    /** Generate a GraphQL schema. Options: {@code pruningMode}. */
    @CEntryPoint(name = "linkml_graphql")
    static CCharPointer graphql(
            IsolateThread thread,
            long handle,
            @CConst CCharPointer options,
            CCharPointerPointer error) {
        return LinkMlCApi.document(handle, options, error, LinkMlNativeApi::graphQl);
    }

    /** Generate a Mermaid entity relationship diagram. Options: {@code pruningMode}, {@code optionalMarker}. */
    @CEntryPoint(name = "linkml_er_diagram")
    static CCharPointer erDiagram(
            IsolateThread thread,
            long handle,
            @CConst CCharPointer options,
            CCharPointerPointer error) {
        return LinkMlCApi.document(handle, options, error, LinkMlNativeApi::erDiagram);
    }

    /** Generate Scala sources, as a JSON object mapping filename to source. Options: {@code package}, {@code generateEmitPrefixes}. */
    @CEntryPoint(name = "linkml_scala")
    static CCharPointer scala(
            IsolateThread thread,
            long handle,
            @CConst CCharPointer options,
            CCharPointerPointer error) {
        return LinkMlCApi.document(handle, options, error, LinkMlNativeApi::scalaFiles);
    }
}

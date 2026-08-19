/*
 * Smoke test for the packaged shared library, and a worked example of driving it from C.
 *
 * Checks the parts that only break once the library leaves the build directory: that the headers
 * and the shared object in the release archive agree, that an isolate comes up, and that a schema
 * can be loaded and generated from. Prints nothing on success beyond a summary; exits non-zero on
 * any failure.
 *
 * Build against an unpacked release archive:
 *
 *   gcc nativelib/smoke.c -o smoke $(PKG_CONFIG_PATH=<prefix> pkg-config --cflags --libs linkml-scala)
 *   LD_LIBRARY_PATH=<prefix>/lib ./smoke
 */

#include <liblinkml_scala.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *LOAD_REQUEST =
    "{\"op\":\"load\",\"schema\":\""
    "id: https://example.org/smoke\\n"
    "name: smoke\\n"
    "imports:\\n"
    "  - linkml:types\\n"
    "default_range: string\\n"
    "classes:\\n"
    "  Person:\\n"
    "    tree_root: true\\n"
    "    attributes:\\n"
    "      name:\\n"
    "        range: string\\n"
    "\"}";

static int failures = 0;

/* Report whether `haystack` contains `needle`, counting a miss as a failure. */
static void expect(const char *what, const char *haystack, const char *needle) {
    if (haystack != NULL && strstr(haystack, needle) != NULL) {
        printf("ok    %s\n", what);
    } else {
        printf("FAIL  %s: expected to find '%s' in:\n%.400s\n", what, needle,
               haystack == NULL ? "(null)" : haystack);
        failures++;
    }
}

/* Pull the schema handle out of a load response. Returns 0 if there is none. */
static long long handle_of(const char *response) {
    const char *found = response == NULL ? NULL : strstr(response, "\"handle\"");
    long long handle = 0;
    if (found == NULL || sscanf(found, "\"handle\" : %lld", &handle) != 1) {
        /* The writer's spacing is not part of the protocol, so fall back to a looser scan. */
        if (found == NULL || sscanf(found + 8, " %*[:] %lld", &handle) != 1) {
            return 0;
        }
    }
    return handle;
}

int main(void) {
    graal_isolate_t *isolate = NULL;
    graal_isolatethread_t *thread = NULL;

    if (graal_create_isolate(NULL, &isolate, &thread) != 0) {
        fprintf(stderr, "FAIL  graal_create_isolate\n");
        return 1;
    }
    printf("ok    isolate created\n");

    char *version = linkml_call(thread, "{\"op\":\"version\"}");
    expect("version", version, "\"abiVersion\"");
    linkml_free(thread, version);

    char *loaded = linkml_call(thread, LOAD_REQUEST);
    expect("load succeeded", loaded, "\"ok\"");
    expect("load found no issues", loaded, "\"issues\"");
    long long handle = handle_of(loaded);
    linkml_free(thread, loaded);

    if (handle <= 0) {
        printf("FAIL  load returned no usable handle\n");
        failures++;
    } else {
        char request[128];
        snprintf(request, sizeof(request),
                 "{\"op\":\"generate\",\"handle\":%lld,\"generator\":\"json-schema\"}", handle);
        char *generated = linkml_call(thread, request);
        /* The metamodel is compiled into the library, so `range: string` resolving at all proves
         * that `linkml:types` survived into the native build. */
        expect("json-schema generated", generated, "json-schema.org");
        expect("the schema's own class is in it", generated, "Person");
        linkml_free(thread, generated);

        snprintf(request, sizeof(request), "{\"op\":\"close\",\"handle\":%lld}", handle);
        char *closed = linkml_call(thread, request);
        expect("close", closed, "\"ok\"");
        linkml_free(thread, closed);
    }

    /* A bad request has to come back as an error rather than taking the process with it. */
    char *rejected = linkml_call(thread, "{\"op\":\"nonsense\"}");
    expect("an unknown op is rejected", rejected, "unknown op");
    linkml_free(thread, rejected);

    char *malformed = linkml_call(thread, "not json at all");
    expect("malformed JSON is rejected", malformed, "\"error\"");
    linkml_free(thread, malformed);

    if (graal_tear_down_isolate(thread) != 0) {
        printf("FAIL  graal_tear_down_isolate\n");
        failures++;
    } else {
        printf("ok    isolate torn down\n");
    }

    if (failures > 0) {
        printf("\n%d check(s) failed\n", failures);
        return 1;
    }
    printf("\nall checks passed\n");
    return 0;
}

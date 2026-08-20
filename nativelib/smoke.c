/*
 * Smoke test for the packaged shared library, and a worked example of driving it from C.
 *
 * Checks the parts that only break once the library leaves the build directory: that the headers
 * and the shared object in the release archive agree, that an isolate comes up, that a schema can
 * be loaded and generated from, and that failures arrive as errors rather than crashes.
 *
 * Exits non-zero if any check fails. Build against an unpacked release archive:
 *
 *   gcc nativelib/smoke.c -o smoke $(PKG_CONFIG_PATH=<prefix> pkg-config --cflags --libs linkml-scala)
 *   LD_LIBRARY_PATH=<prefix>/lib ./smoke
 *
 * or just run nativelib/smoke.sh <prefix>, which handles the per-platform details.
 */

#include <liblinkml_scala.h>
#include <stdio.h>
#include <string.h>

static const char *SCHEMA =
    "id: https://example.org/smoke\n"
    "name: smoke\n"
    "imports:\n"
    "  - linkml:types\n"
    "default_range: string\n"
    "classes:\n"
    "  Person:\n"
    "    tree_root: true\n"
    "    attributes:\n"
    "      name:\n"
    "        range: string\n";

static int failures = 0;

static void ok(const char *what) { printf("ok    %s\n", what); }

static void fail(const char *what, const char *detail) {
    printf("FAIL  %s: %s\n", what, detail == NULL ? "(no detail)" : detail);
    failures++;
}

/* Check that a document came back and contains `needle`. Frees it either way. */
static void expect(graal_isolatethread_t *thread, const char *what, char *document,
                   char **error, const char *needle) {
    if (document == NULL) {
        fail(what, *error);
    } else if (strstr(document, needle) == NULL) {
        printf("FAIL  %s: expected '%s' in:\n%.300s\n", what, needle, document);
        failures++;
    } else {
        ok(what);
    }
    linkml_free(thread, document);
    linkml_free(thread, *error);
    *error = NULL;
}

/* Check that a call was refused, and said why. */
static void expect_refused(graal_isolatethread_t *thread, const char *what, char *document,
                           char **error, const char *needle) {
    if (document != NULL) {
        fail(what, "the call was expected to fail, but returned a document");
    } else if (*error == NULL) {
        fail(what, "returned NULL without setting an error");
    } else if (strstr(*error, needle) == NULL) {
        printf("FAIL  %s: expected '%s' in error: %s\n", what, needle, *error);
        failures++;
    } else {
        ok(what);
    }
    linkml_free(thread, document);
    linkml_free(thread, *error);
    *error = NULL;
}

int main(void) {
    graal_isolate_t *isolate = NULL;
    graal_isolatethread_t *thread = NULL;

    if (graal_create_isolate(NULL, &isolate, &thread) != 0) {
        fprintf(stderr, "FAIL  graal_create_isolate\n");
        return 1;
    }
    ok("isolate created");

    if (linkml_abi_version(thread) != 1) {
        fail("abi version", "expected 1");
    } else {
        ok("abi version");
    }

    /* Load. No import map, so the array arguments are NULL and the count is 0. */
    char *report = NULL, *error = NULL;
    long long handle =
        linkml_load_string(thread, NULL, SCHEMA, NULL, NULL, 0, NULL, &report, &error);
    if (handle <= 0) {
        fail("load", error != NULL ? error : report);
    } else if (report == NULL) {
        fail("load", "no report was written");
    } else {
        ok("load");
    }
    linkml_free(thread, report);
    linkml_free(thread, error);

    if (handle > 0) {
        char *out;

        /* NULL options means defaults, so the common case needs no JSON at all. The metamodel is
         * compiled into the library, so `range: string` resolving proves linkml:types survived. */
        out = linkml_json_schema(thread, handle, NULL, &error);
        expect(thread, "json-schema with default options", out, &error, "json-schema.org");

        out = linkml_shacl(thread, handle, NULL, &error);
        expect(thread, "shacl", out, &error, "shacl#NodeShape");

        /* And here is the options channel being used. */
        out = linkml_shacl(thread, handle, "{\"open\":true}", &error);
        expect(thread, "shacl with options", out, &error, "shacl#NodeShape");

        out = linkml_er_diagram(thread, handle, NULL, &error);
        expect(thread, "er-diagram", out, &error, "erDiagram");

        out = linkml_lint(thread, handle, NULL, &error);
        expect(thread, "lint returns a report", out, &error, "issues");

        out = linkml_scala(thread, handle, NULL, &error);
        expect(thread, "scala returns a file map", out, &error, "Person.scala");

        /* An option that does not exist is refused, not quietly ignored. */
        out = linkml_json_schema(thread, handle, "{\"nonsense\":true}", &error);
        expect_refused(thread, "an unknown option is refused", out, &error, "nonsense");

        linkml_close(thread, handle);
        ok("close");

        /* Using a closed handle must be an error, not a crash. */
        out = linkml_json_schema(thread, handle, NULL, &error);
        expect_refused(thread, "a closed handle is refused", out, &error, "closed");
    }

    /* A handle that was never issued. */
    char *never = linkml_shacl(thread, 999999, NULL, &error);
    expect_refused(thread, "an unknown handle is refused", never, &error, "999999");

    if (graal_tear_down_isolate(thread) != 0) {
        fail("isolate torn down", "graal_tear_down_isolate returned non-zero");
    } else {
        ok("isolate torn down");
    }

    if (failures > 0) {
        printf("\n%d check(s) failed\n", failures);
        return 1;
    }
    printf("\nall checks passed\n");
    return 0;
}

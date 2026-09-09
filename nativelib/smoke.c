/*
 * Smoke test for the packaged shared library, and a worked example of driving it from C.
 *
 * Checks the parts that only break once the library leaves the build directory: that the headers
 * and the shared object in the release archive agree, that the runtime starts, that a schema can
 * be loaded and generated from, and that failures arrive as errors rather than crashes.
 *
 * Exits non-zero if any check fails. Build against an unpacked release archive:
 *
 *   gcc nativelib/smoke.c -o smoke $(PKG_CONFIG_PATH=<prefix> pkg-config --cflags --libs linkml-scala)
 *   LD_LIBRARY_PATH=<prefix>/lib ./smoke
 *
 * or just run nativelib/smoke.sh <prefix>, which handles the per-platform details.
 */

#include <linkml_scala.h>
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
static void expect(const char *what, char *document,
                   char **error, const char *needle) {
    if (document == NULL) {
        fail(what, *error);
    } else if (strstr(document, needle) == NULL) {
        printf("FAIL  %s: expected '%s' in:\n%.300s\n", what, needle, document);
        failures++;
    } else {
        ok(what);
    }
    linkml_free(document);
    linkml_free(*error);
    *error = NULL;
}

/* Check that a call was refused, and said why. */
static void expect_refused(const char *what, char *document,
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
    linkml_free(document);
    linkml_free(*error);
    *error = NULL;
}

int main(void) {
    if (linkml_init_threads() != 0) {
        fprintf(stderr, "FAIL  linkml_init_threads\n");
        return 1;
    }
    ok("runtime started");

    if (linkml_abi_version() != LINKML_ABI_VERSION) {
        fail("abi version", "does not match the header");
    } else {
        ok("abi version");
    }

    /* Build metadata needs no schema, so it goes before loading one. */
    {
        char *error = NULL;
        char *out = linkml_build_info(&error);
        expect("build info", out, &error, "linkml_scala_version");
    }

    /* Load. No import map, so the array arguments are NULL and the count is 0. */
    char *report = NULL, *error = NULL;
    long long handle =
        linkml_load_string(NULL, SCHEMA, NULL, NULL, 0, NULL, &report, &error);
    if (handle <= 0) {
        fail("load", error != NULL ? error : report);
    } else if (report == NULL) {
        fail("load", "no report was written");
    } else {
        ok("load");
    }
    linkml_free(report);
    linkml_free(error);

    if (handle > 0) {
        char *out;

        /* NULL options means defaults, so the common case needs no JSON at all. The metamodel is
         * compiled into the library, so `range: string` resolving proves linkml:types survived. */
        out = linkml_json_schema(handle, NULL, &error);
        expect("json-schema with default options", out, &error, "json-schema.org");

        /* Turtle by default, so the vocabularies come out prefixed. */
        out = linkml_shacl(handle, NULL, &error);
        expect("shacl", out, &error, "a sh:NodeShape");

        /* And here is the options channel being used. `open` turns sh:closed off, so this checks
         * that the option arrived rather than just that something came back. */
        out = linkml_shacl(handle, "{\"open\":true}", &error);
        expect("shacl with options", out, &error, "sh:closed false");

        /* The format is an option like any other, and picks the other serialization. */
        out = linkml_shacl(handle, "{\"format\":\"nt\"}", &error);
        expect("shacl as n-triples", out, &error, "shacl#NodeShape");

        out = linkml_er_diagram(handle, NULL, &error);
        expect("er-diagram", out, &error, "erDiagram");

        out = linkml_lint(handle, NULL, &error);
        expect("lint returns a report", out, &error, "issues");

        out = linkml_scala(handle, NULL, &error);
        expect("scala returns a file map", out, &error, "Person.scala");

        /* An option that does not exist is refused, not quietly ignored. */
        out = linkml_json_schema(handle, "{\"nonsense\":true}", &error);
        expect_refused("an unknown option is refused", out, &error, "nonsense");

        linkml_close(handle);
        ok("close");

        /* Using a closed handle must be an error, not a crash. */
        out = linkml_json_schema(handle, NULL, &error);
        expect_refused("a closed handle is refused", out, &error, "closed");
    }

    /* A handle that was never issued. */
    char *never = linkml_shacl(999999, NULL, &error);
    expect_refused("an unknown handle is refused", never, &error, "999999");

    if (failures > 0) {
        printf("\n%d check(s) failed\n", failures);
        return 1;
    }
    printf("\nall checks passed\n");
    return 0;
}

/* LinkML-Scala: schema validation and multi-format code generation, as a C library.
 *
 * Two conventions run through the whole API:
 *
 *   - Options are one JSON string, and may be NULL for defaults.
 *   - Failure is NULL plus a message. A function returning a string returns NULL and writes the
 *     reason to *error; loading returns handle 0 instead. Both *error and *report are cleared
 *     first, so they can be reused across calls. A NULL return with no message is still a failure:
 *     it means even the message could not be allocated.
 *
 * Every string the library returns belongs to the caller and must go back through linkml_free().
 * Strings in and out are NUL-terminated UTF-8.
 *
 * Threading: the library must be used from one thread. Scala Native cannot register a thread it
 * did not create (scala-native#4951), so a caller with several threads has to funnel calls through
 * one of its own, as the Python bindings do. Call linkml_init_threads() once from that thread
 * before anything else.
 */

#ifndef LINKML_SCALA_H
#define LINKML_SCALA_H

#ifdef __cplusplus
extern "C" {
#endif

/* Bumped whenever a change to the exported functions or to the options JSON breaks callers. */
#define LINKML_ABI_VERSION 3

/* The ABI version this library was built with. Compare against LINKML_ABI_VERSION. */
int linkml_abi_version(void);

/* Correct the stack bounds recorded when the library was loaded, and claim the calling thread.
 * Call once, from the thread that loaded the library and that will make every call. */
int linkml_init_threads(void);

/* Version and build metadata for this library, as a BuildInfo document in JSON. */
char *linkml_build_info(char **error);

/* Parse a schema from the file system, resolving imports from disk like the CLI does.
 * Returns a handle, or 0 with the reason in *report (the schema was refused) or *error. */
long long linkml_load_file(const char *path, const char *options, char **report, char **error);

/* Parse a schema from memory, resolving imports against a caller-supplied map given as two
 * parallel arrays. Pass path to read the root through the map too, which makes loading immune to
 * an import that references the root back; otherwise pass NULL and give the text in schema. */
long long linkml_load_string(const char *path, const char *schema, char **import_names,
                             char **import_bodies, int import_count, const char *options,
                             char **report, char **error);

/* Release a schema handle. Closing one that is already gone is not an error. */
void linkml_close(long long handle);

/* Re-run the validator over a loaded schema, as a SchemaValidationReport in JSON. */
char *linkml_lint(long long handle, const char *options, char **error);

/* Generators. Each takes a schema handle and returns one document. */
char *linkml_json_schema(long long handle, const char *options, char **error);
char *linkml_shacl(long long handle, const char *options, char **error);
char *linkml_rdfs(long long handle, const char *options, char **error);
char *linkml_linkml(long long handle, const char *options, char **error);
char *linkml_graphql(long long handle, const char *options, char **error);
char *linkml_er_diagram(long long handle, const char *options, char **error);

/* Generators producing several files, returned as a JSON object of filename to content. */
char *linkml_scala(long long handle, const char *options, char **error);
char *linkml_frictionless(long long handle, const char *options, char **error);

/* Release anything the library returned. NULL-safe. */
void linkml_free(char *buffer);

#ifdef __cplusplus
}
#endif

#endif /* LINKML_SCALA_H */

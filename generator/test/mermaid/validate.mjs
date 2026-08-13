// Validates generated Mermaid ER diagrams against the real Mermaid parser.
//
// Used by ErDiagramMermaidSpec, which writes one `<name>.mmd` file per model into a directory and
// runs `node validate.mjs <dir> <out-file>`. For every input file one NDJSON line is written to
// <out-file>:
//
//   {"name": "...", "ok": true,  "entities": [...], "relationships": [...]}
//   {"name": "...", "ok": false, "error": "..."}
//
// A file rather than stdout because a single line can run to hundreds of kilobytes for a schema the
// size of the LinkML metamodel, which a stdout pipe truncates.
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";
import mermaid from "mermaid";

const [dir, outFile] = process.argv.slice(2);
if (!dir || !outFile) {
  console.error("usage: node validate.mjs <directory-of-.mmd-files> <out-file>");
  process.exit(2);
}

/** Mermaid keys entities by an internal `entity-<name>-<index>` id in relationships. */
const entityName = (id) => {
  const m = /^entity-(.*)-\d+$/.exec(id);
  return m ? m[1] : id;
};

const files = readdirSync(dir).filter((f) => f.endsWith(".mmd")).sort();
const lines = [];

for (const file of files) {
  const name = basename(file, ".mmd");
  const text = readFileSync(join(dir, file), "utf-8");
  try {
    // `parse` runs Mermaid's own preprocessing (frontmatter, directives, `%%` comments) before the
    // grammar, which is what every real consumer of the output does.
    await mermaid.parse(text, { suppressErrors: false });
    const { db } = await mermaid.mermaidAPI.getDiagramFromText(text);
    const entities = [...db.getEntities().entries()].map(([id, e]) => ({
      name: id,
      attributes: e.attributes.map((a) => ({
        // Named `dataType` rather than `type` so the Scala side can decode it without escaping.
        dataType: a.type,
        name: a.name,
        keys: a.keys ?? [],
      })),
    }));
    const relationships = db.getRelationships().map((r) => ({
      from: entityName(r.entityA),
      to: entityName(r.entityB),
      label: r.roleA,
      // Mermaid stores the *first* glyph in cardB and the *second* in cardA.
      fromCardinality: r.relSpec.cardB,
      toCardinality: r.relSpec.cardA,
      identifying: r.relSpec.relType === "IDENTIFYING",
    }));
    lines.push(JSON.stringify({ name, ok: true, entities, relationships }));
  } catch (e) {
    lines.push(JSON.stringify({ name, ok: false, error: String(e && e.message ? e.message : e) }));
  }
}

if (files.length === 0) {
  console.error(`no .mmd files found in ${dir}`);
  process.exit(2);
}

writeFileSync(outFile, lines.join("\n") + "\n", "utf-8");
// The spec turns each line into an assertion, so a parse failure must not fail the process here.
process.exit(0);

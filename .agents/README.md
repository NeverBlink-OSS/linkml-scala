# LinkML agent skill

An [agent skill](https://agentskills.io/specification) for authoring LinkML schemas with
[linkml-scala](https://github.com/NeverBlink-OSS/linkml-scala). One skill, several topic files:
[`skills/linkml/SKILL.md`](skills/linkml/SKILL.md) carries the always-loaded guidance and routes
to the rest on demand.

Works with **OpenAI Codex** (which reads `.agents/skills/` natively) and **Claude Code**. Requires
the `linkml-scala` CLI, 0.12.0 or newer — the skill walks the user through installing it.

## Installing

### Codex

Codex discovers `.agents/skills/` in the repository you are working in, or `~/.agents/skills/`
for every project:

```shell
git clone --depth=1 https://github.com/NeverBlink-OSS/linkml-scala /tmp/linkml-scala
mkdir -p ~/.agents/skills
cp -r /tmp/linkml-scala/.agents/skills/linkml ~/.agents/skills/
```

Project-level `.agents/skills/` is the more reliable of the two today — there are open reports of
user-level skills intermittently not being indexed. This repository can also be added as a Codex
plugin marketplace via [`plugins/marketplace.json`](plugins/marketplace.json).

### Claude Code

```
/plugin marketplace add NeverBlink-OSS/linkml-scala
/plugin install linkml@linkml-scala
```

Claude Code does **not** read `.agents/skills/`, so a manual install needs a copy or a symlink.
Symlinks are supported and let `git pull` update the skill in place:

```shell
git clone --depth=1 https://github.com/NeverBlink-OSS/linkml-scala ~/src/linkml-scala
mkdir -p ~/.claude/skills
ln -s ~/src/linkml-scala/.agents/skills/linkml ~/.claude/skills/linkml
```

### Any other agent

Copy `skills/linkml/` wherever your tool looks for skills. It is self-contained, and the
frontmatter sticks to the fields in the Agent Skills spec, so it should load anywhere.

## Layout

```
.agents/
├── skills/linkml/
│   ├── SKILL.md                  always loaded; guidance + routed index
│   ├── 100-authoring.md          slots, inheritance, constraints, enums, keys, imports, URIs
│   ├── 200-limitations.md        GENERATED - unsupported features, divergent semantics
│   ├── 300-bootstrap.md          converting RDFS/OWL, SHACL, JSON Schema, XSD, sample data
│   ├── 400-review.md             modelling-quality review checklist
│   ├── 500-validate-data.md      validating instance data
│   ├── 600-ci.md                 GitHub Actions, with templates in assets/
│   ├── 900-metaslots.tsv         GENERATED - the metamodel vocabulary, for grepping
│   ├── 910-examples.md           GENERATED - known-good example schemas by feature
│   ├── 990-install.md            install options
│   └── assets/                   copy-and-adapt CI workflows
├── .claude-plugin/plugin.json
├── .codex-plugin/plugin.json
├── plugins/marketplace.json      Codex plugin marketplace
├── generate.py                   regenerates the GENERATED files
└── check.py                      validates the skill
```

Topic files are numbered by theme so the index reads in a stable order: 100s authoring, 200s
limits and validation, 300–600 workflows, 900s lookup tables.

## Maintaining

Three files are derived from sources in this repository, so they cannot drift from the
implementation they describe:

| File | Source |
|---|---|
| `900-metaslots.tsv` | the real LinkML metamodel YAML, fetched by the build |
| `200-limitations.md` | `docs/implementation_differences.md` |
| `910-examples.md` | `tests/resources/models/` |

```shell
./mill show metamodel.definitions      # fetch the metamodel YAML (not committed)
python3 .agents/generate.py            # write the derived files
python3 .agents/generate.py --check    # CI: fail if stale
python3 .agents/check.py               # frontmatter, links, and every schema example
```

`check.py` runs `linkml-scala validate --strict` over every ` ```yaml ` block in the skill, so a
documented schema that does not validate fails CI. It also rejects frontmatter outside the Agent
Skills spec, Claude-Code-only syntax that Codex cannot interpret, and links that go nowhere. Both
run in the `skills-check` job of [`checks.yml`](../.github/workflows/checks.yml).

There is deliberately **no** bundled CLI reference and no validator-issue catalogue: `--help` and
the validator's own messages are self-describing, so a copy would only rot.

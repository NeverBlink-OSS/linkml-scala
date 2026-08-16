# LinkML agent skill

An [agent skill](https://agentskills.io/specification) for authoring LinkML schemas with
[linkml-scala](https://github.com/NeverBlink-OSS/linkml-scala). One skill, several topic files:
[`skills/linkml/SKILL.md`](skills/linkml/SKILL.md) carries the always-loaded guidance and routes
to the rest on demand.

Works with **OpenAI Codex** (which reads `.agents/skills/` natively) and **Claude Code**. Requires
the `linkml-scala` CLI, 0.12.0 or newer — the skill walks the user through installing it.

## Installing

### Codex

Add this repository as a plugin marketplace, then install **linkml** from `/plugins`:

```shell
codex plugin marketplace add NeverBlink-OSS/linkml-scala
codex plugin marketplace upgrade linkml-scala   # if you added it before
```

Codex also discovers `.agents/skills/` in the repository you are working in, or
`~/.agents/skills/` for every project, if you would rather copy the skill in:

```shell
git clone --depth=1 https://github.com/NeverBlink-OSS/linkml-scala /tmp/linkml-scala
mkdir -p ~/.agents/skills
cp -r /tmp/linkml-scala/.agents/skills/linkml ~/.agents/skills/
```

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

Version numbers are checked too. The requirement in
[`990-install.md`](skills/linkml/990-install.md) is a feature floor, so it moves only when the
skill starts relying on something newer — but every restatement of it, here included, has to
move with it. The install commands deliberately name no version at all (`mise use --pin`
resolves the newest release and records it), so there is no number left to go stale.

Neither plugin manifest carries a `version`. Both Claude Code and Codex treat a declared version
as the cache key, so a fixed one pins every user to the copy they first installed. Left out,
Claude Code falls back to the commit SHA and picks up each change to this directory.

There is deliberately **no** bundled CLI reference and no validator-issue catalogue: `--help` and
the validator's own messages are self-describing, so a copy would only rot.

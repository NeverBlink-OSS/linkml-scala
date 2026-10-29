# Agent skills

These skills help agents acquire ontologies and maintain project selections with ohi, and author and validate schemas with ohi LinkML.

## Choose a plugin

Install **ohi** (`ohi`) for ontology acquisition, project selections and LinkML schema work.
Choose **LinkML** (`linkml`) for schema work alone. Install one plugin, since ohi already includes LinkML.

Plugins provide instructions. [Install the ohi CLI](../cli/ohi/README.md#install) separately from
the same release or checkout. The standalone LinkML skill requires `ohi` 0.17.1 or newer.

## Install

These commands install ohi. For LinkML alone, replace `ohi@linkml-scala` with `linkml@linkml-scala`.
To install from a local checkout, replace the repository argument with its absolute path.

### Codex

```sh
codex plugin marketplace add NeverBlink-OSS/linkml-scala
codex plugin add ohi@linkml-scala
```

### Claude Code

```sh
claude plugin marketplace add NeverBlink-OSS/linkml-scala
claude plugin install ohi@linkml-scala
```

### Updates and switching

| Application | Refresh the marketplace | Update the installed plugin |
| --- | --- | --- |
| Codex | `codex plugin marketplace upgrade linkml-scala` | Included in the marketplace upgrade |
| Claude Code | `claude plugin marketplace update linkml-scala` | `claude plugin update ohi@linkml-scala` |

Start a new session after updating. For a local checkout marketplace, remove and reinstall the
plugin after editing its files.

When switching choices, remove the installed plugin first, then install the other choice. For example, to switch from LinkML to ohi + LinkML:

```sh
codex plugin remove linkml@linkml-scala
codex plugin add ohi@linkml-scala
```

In Claude Code, use `claude plugin uninstall linkml@linkml-scala` followed by `claude plugin install ohi@linkml-scala`. Reverse the plugin names to switch back.

### Manual installation

Without a plugin, copy complete skill folders, including their references, to the host's skills directory.
For ohi, copy both `linkml` and `ohi`. For example, install both for all Codex projects:

```sh
git clone --depth=1 https://github.com/NeverBlink-OSS/linkml-scala ~/src/linkml-scala
mkdir -p ~/.agents/skills
cp -r ~/src/linkml-scala/.agents/skills/{linkml,ohi} ~/.agents/skills/
```

For Claude Code, use `~/.claude/skills/`. For project-only installation, use `.agents/skills/`
for Codex or `.claude/skills/` for Claude Code in that project. Replace whole folders when updating.
Use either manual installation or the plugin to avoid duplicate skills.

### Other applications

For other applications supporting [Agent Skills](https://agentskills.io/specification), use their
documented skills directory.

## ohi

Give the agent search terms or a publication URL, the required format and a download destination.
For example, ask it to find a provenance ontology and download its `owl-turtle` artifact.
Search uses ohi.dev by default. For project changes, identify the project directory.
The [ohi skill](skills/ohi/SKILL.md) and its [CLI reference](skills/ohi/references/cli.md) cover
selection, downloads and recovery.

## LinkML

The [LinkML skill](skills/linkml/SKILL.md) covers schema authoring, validation, importing and generation. Its topic files provide detailed guidance when needed. [Installation options](skills/linkml/990-install.md) cover the `ohi` CLI, which runs every LinkML command as `ohi linkml <command>`.

## Maintenance

Edit skills in `.agents/skills/`. Regenerate these LinkML references from their sources:

| File | Source |
| --- | --- |
| `900-metaslots.tsv` | LinkML metamodel bundled with the `metamodel` module |
| `200-limitations.md` | `website/src/content/docs/implementation-differences.md` |
| `210-slot-usage-inheritance.md` | `website/src/content/docs/slot-usage-inheritance.md` |
| `220-import-maps.md` | `website/src/content/docs/import-maps.md` |
| `230-curies.md` | `website/src/content/docs/curies.md` |
| `910-examples.md` | `tests/resources/models/` |

```sh
./mill agents.generate
./mill agents.test
```

`./mill regenerate` also runs `agents.generate`. The tests check that the generated files are up to date, the skills' structure and links, and that every schema example validates. Review nested ohi reference links separately.

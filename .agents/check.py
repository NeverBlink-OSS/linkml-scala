#!/usr/bin/env python3
"""Check the agent skills are well-formed and that every schema example still works.

Two independent passes:

1. **Structure** - each skill has a SKILL.md whose frontmatter stays inside the six fields
   of the Agent Skills spec (anything else breaks portability to Codex and to the
   claude.ai Skills API), whose `name` matches its directory, and whose body avoids
   Claude-Code-only syntax that Codex cannot interpret.

2. **Examples** - every ```yaml block in every SKILL.md and reference is fed to
   `linkml-scala validate --strict`. A documented schema that does not validate teaches
   an agent to write broken schemas, so this is a hard failure.

   Blocks are usually fragments, so each is wrapped in a minimal envelope. Top-level
   `slots:`, `settings:` and `prefixes:` accumulate across a file, because later fragments
   refer back to them; `classes:` and `enums:` do not, because consecutive blocks are
   often alternative illustrations of the same thing rather than one growing schema.

Requires `linkml-scala` on PATH; skips the example pass with a warning if it is missing.

Usage:
    python3 .agents/check.py
"""

from __future__ import annotations

import copy
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

AGENTS = Path(__file__).resolve().parent
SKILLS = AGENTS / "skills"
REPO = AGENTS.parent

# https://agentskills.io/specification - anything outside this set is rejected by the
# claude.ai Skills API and is meaningless to Codex.
ALLOWED_FRONTMATTER = {
    "name",
    "description",
    "license",
    "compatibility",
    "metadata",
    "allowed-tools",
}

# Claude Code extensions that Codex does not implement. A skill relying on one of these
# silently misbehaves there instead of failing loudly.
CLAUDE_ONLY = [
    (re.compile(r"\$ARGUMENTS\b"), "$ARGUMENTS"),
    (re.compile(r"\$\{CLAUDE_[A-Z_]+\}"), "${CLAUDE_*}"),
    (re.compile(r"^\s*```!", re.M), "```! dynamic block"),
    (re.compile(r"(?<!`)!`[^`\n]+`"), "!`cmd` injection"),
]

ENVELOPE = {
    "id": "https://example.org/skill-check",
    "name": "skill_check",
    "prefixes": {
        "ex": "https://example.org/",
        "linkml": "https://w3id.org/linkml/",
        "schema": "http://schema.org/",
        "foaf": "http://xmlns.com/foaf/0.1/",
        "dcterms": "http://purl.org/dc/terms/",
    },
    "default_prefix": "ex",
    "imports": ["linkml:types"],
}

# Classes the docs reference in passing without defining them in the same block.
STUBS = {
    "Person": {"attributes": {"name": {"range": "string"}}},
    "Agent": {"attributes": {"agent_id": {"range": "string"}}},
}

errors: list[str] = []
stats = {"files": 0, "blocks": 0, "links": 0}


def fail(where: object, message: str) -> None:
    errors.append(f"{where}: {message}")


# ------------------------------------------------------------------------- structure


def split_frontmatter(text: str) -> tuple[dict | None, str]:
    if not text.startswith("---\n"):
        return None, text
    end = text.find("\n---\n", 3)
    if end == -1:
        return None, text
    return yaml.safe_load(text[4:end]) or {}, text[end + 5 :]


def check_structure(skill_dir: Path) -> None:
    rel = skill_dir.relative_to(REPO)
    skill_md = skill_dir / "SKILL.md"
    if not skill_md.is_file():
        fail(rel, "no SKILL.md")
        return

    front, body = split_frontmatter(skill_md.read_text())
    if front is None:
        fail(f"{rel}/SKILL.md", "missing or malformed YAML frontmatter")
        return

    extra = set(front) - ALLOWED_FRONTMATTER
    if extra:
        fail(
            f"{rel}/SKILL.md",
            f"frontmatter keys outside the Agent Skills spec: {', '.join(sorted(extra))}",
        )

    name = front.get("name")
    if not name:
        fail(f"{rel}/SKILL.md", "frontmatter has no `name`")
    elif name != skill_dir.name:
        fail(f"{rel}/SKILL.md", f"name '{name}' does not match directory '{skill_dir.name}'")
    elif not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", name):
        fail(f"{rel}/SKILL.md", f"name '{name}' is not lowercase-hyphenated")

    desc = front.get("description", "")
    if not desc:
        fail(f"{rel}/SKILL.md", "frontmatter has no `description` - the skill will never trigger")
    elif len(desc) > 1024:
        fail(f"{rel}/SKILL.md", f"description is {len(desc)} chars; keep it well under 1024")

    for pattern, label in CLAUDE_ONLY:
        if pattern.search(body):
            fail(f"{rel}/SKILL.md", f"uses Claude-Code-only syntax ({label}); Codex cannot run it")

    lines = body.count("\n")
    if lines > 500:
        fail(f"{rel}/SKILL.md", f"body is {lines} lines; the guidance is to stay under 500")

    # Every referenced reference file must exist, or the skill sends the agent nowhere.
    check_links(skill_dir / "SKILL.md")


def check_links(md: Path) -> None:
    """Every relative link inside the skill must resolve, or it sends the agent nowhere."""
    if not md.is_file():
        return
    rel = md.relative_to(REPO)
    for target in re.findall(r"\]\(([^)#:]+)\)", md.read_text()):
        target = target.strip()
        if target.startswith(("http", "mailto:", "/", "#")):
            continue
        stats["links"] += 1
        if not (md.parent / target).exists():
            fail(rel, f"links to {target}, which does not exist")


# -------------------------------------------------------------------------- examples


def wrap(fragment: dict, carried: dict) -> dict | None:
    doc = copy.deepcopy(ENVELOPE)
    doc.update({k: copy.deepcopy(v) for k, v in carried.items()})
    for key, value in fragment.items():
        if key == "imports":
            continue  # relative imports point at files that are not on disk
        if key == "prefixes":
            doc["prefixes"] = {**doc["prefixes"], **value}
        elif key in ("slots", "settings") and isinstance(value, dict):
            doc[key] = {**doc.get(key, {}), **value}
        else:
            doc[key] = value

    classes = doc.get("classes")
    if not classes:
        return None

    rendered = yaml.dump(doc)
    for stub_name, stub_body in STUBS.items():
        if re.search(rf"\b{stub_name}\b", rendered) and stub_name not in classes:
            classes[stub_name] = copy.deepcopy(stub_body)

    # --strict warns without a tree root, which is noise for a fragment.
    if not any((c or {}).get("tree_root") for c in classes.values()):
        first = next(iter(classes))
        classes[first] = {**(classes[first] or {}), "tree_root": True}
    return doc


def check_examples(md: Path, tmp: Path) -> None:
    rel = md.relative_to(REPO)
    blocks = re.findall(r"```yaml\n(.*?)```", md.read_text(), re.S)
    stats["files"] += 1
    carried: dict = {}
    for index, block in enumerate(blocks):
        label = f"{rel} block {index + 1}"
        try:
            fragment = yaml.safe_load(block)
        except yaml.YAMLError as exc:
            fail(label, f"is not valid YAML: {exc}")
            continue
        if not isinstance(fragment, dict):
            continue

        # Carry forward only the definitions later fragments refer back to.
        for key in ("slots", "settings"):
            if isinstance(fragment.get(key), dict):
                carried[key] = {**carried.get(key, {}), **fragment[key]}
        if isinstance(fragment.get("prefixes"), dict):
            carried["prefixes"] = {**ENVELOPE["prefixes"], **fragment["prefixes"]}

        doc = wrap(fragment, carried)
        if doc is None:
            continue

        stats["blocks"] += 1
        path = tmp / f"{md.stem}-{index:02d}.yaml"
        path.write_text(yaml.dump(doc, sort_keys=False))
        proc = subprocess.run(
            ["linkml-scala", "validate", "--strict", "--format", "plain", str(path)],
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            detail = " | ".join(
                line.strip()
                for line in (proc.stdout + proc.stderr).splitlines()
                if re.search(r"FATAL|ERROR|WARNING", line)
            )
            fail(label, f"does not validate: {detail or proc.stdout.strip()}")


def main() -> int:
    skill_dirs = sorted(p for p in SKILLS.iterdir() if p.is_dir() and (p / "SKILL.md").is_file())
    if not skill_dirs:
        print("no skills found", file=sys.stderr)
        return 1

    for skill_dir in skill_dirs:
        check_structure(skill_dir)

    if shutil.which("linkml-scala") is None:
        print("warning: linkml-scala not on PATH - skipping the schema example pass", file=sys.stderr)
    else:
        with tempfile.TemporaryDirectory() as raw_tmp:
            tmp = Path(raw_tmp)
            for skill_dir in skill_dirs:
                for md in sorted(skill_dir.glob("*.md")):
                    check_examples(md, tmp)
                for md in sorted(skill_dir.glob("*.md")):
                    check_links(md)

    # The CI workflow templates have to be parseable, or they fail only once a user
    # copies them into their own repository.
    for asset in sorted(SKILLS.glob("*/assets/*.yml")):
        try:
            yaml.safe_load(asset.read_text())
        except yaml.YAMLError as exc:
            fail(asset.relative_to(REPO), f"is not valid YAML: {exc}")

    if errors:
        print(f"{len(errors)} problem(s):\n", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        return 1

    print(
        f"OK: {len(skill_dirs)} skill(s), {stats['files']} markdown files, "
        f"{stats['links']} internal links, {stats['blocks']} schema examples validated"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

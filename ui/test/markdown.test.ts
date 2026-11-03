// Tests for markdown.ts. Run with `npm test` (see run.mjs).
import { test } from "node:test";
import assert from "node:assert/strict";
import { addWordBreaks, PageSections, renderMarkdown } from "../src/markdown.js";

test("the first cell of each table row is a name that can go onto more lines", () => {
  const html = renderMarkdown(
    "| Class | Description |\n| --- | --- |\n| [RegisteredShare](#registeredshare) | A share_class |\n",
  );
  assert.ok(html.includes("<th>Class</th>"), html);
  assert.ok(
    html.includes(
      '<td><span class="md-name"><a href="#registeredshare">Registered<wbr>Share</a></span></td>',
    ),
    html,
  );
  // Only the first cell gets word breaks.
  assert.ok(html.includes("<td>A share_class</td>"), html);
});

test("the alignment of a first cell is kept", () => {
  const html = renderMarkdown("| Name |\n| :-: |\n| a_b |\n");
  assert.ok(html.includes('<td align="center"><span class="md-name">a_<wbr>b</span></td>'), html);
});

test("word breaks go between words only", () => {
  assert.equal(addWordBreaks("hasPart2Of"), "has<wbr>Part2<wbr>Of");
  assert.equal(addWordBreaks("has_part_"), "has_<wbr>part_");
  assert.equal(addWordBreaks("ABCShare"), "ABCShare");
});

test("word breaks leave tags and character references as they are", () => {
  assert.equal(
    addWordBreaks('<a href="#aB">fooBar</a> &amp;Copy &ApplyFunction;'),
    '<a href="#aB">foo<wbr>Bar</a> &amp;Copy &ApplyFunction;',
  );
});

test("a link to a section stays in the page, and other links open in a new tab", () => {
  assert.equal(renderMarkdown("[a](#a)"), '<p><a href="#a">a</a></p>\n');
  assert.equal(
    renderMarkdown("[a](https://w3id.org/linkml) [b](spec.md)"),
    '<p><a target="_blank" rel="noopener" href="https://w3id.org/linkml">a</a> ' +
      '<a target="_blank" rel="noopener" href="spec.md">b</a></p>\n',
  );
});

const DOC = '# Schema\n\nIndex.\n\n<a id="class-a"></a>\n\n## A\n\nAbout A.\n\n<a id="class-b"></a>\n\n## B\n';

test("a document splits into the index and a section for each anchor", () => {
  const patch = new PageSections().update(DOC, 0);
  assert.deepEqual(patch.keys, ["", "class-a", "class-b"]);
  assert.deepEqual(patch.revs, [1, 2, 3]);
  assert.equal(patch.rev, 3);
  assert.equal(patch.html.get(""), "<h1>Schema</h1>\n<p>Index.</p>\n");
  assert.equal(patch.html.get("class-a"), '<p><a id="class-a"></a></p>\n<h2>A</h2>\n<p>About A.</p>\n');
  assert.equal(patch.lines.get("class-a"), 7);
  // The sections together are the whole document.
  assert.equal([...patch.html.values()].join(""), renderMarkdown(DOC));
});

test("a patch has the HTML only of the sections that are newer than the page has", () => {
  const sections = new PageSections();
  const first = sections.update(DOC, 0);
  const patch = sections.update(DOC.replace("About A.", "About A!"), first.rev);
  assert.deepEqual(patch.keys, ["", "class-a", "class-b"]);
  assert.deepEqual(patch.revs, [1, 4, 3]);
  assert.deepEqual([...patch.html.keys()], ["class-a"]);
  assert.equal(patch.rev, 4);
});

test("a patch also has the sections of a patch that the page did not show", () => {
  const sections = new PageSections();
  const shown = sections.update(DOC, 0);
  // Not shown, as a newer request replaced it
  sections.update(DOC.replace("About A.", "About A!"), shown.rev);
  const patch = sections.update(DOC.replace("About A.", "About A!").replace("## B", "## B!"), shown.rev);
  assert.deepEqual([...patch.html.keys()], ["class-a", "class-b"]);
});

test("a page that shows nothing gets every section", () => {
  const sections = new PageSections();
  sections.update(DOC, 0);
  assert.deepEqual([...sections.update(DOC, 0).html.keys()], ["", "class-a", "class-b"]);
});

test("sections keep their keys when others are added, removed or moved", () => {
  const sections = new PageSections();
  const first = sections.update(DOC, 0);
  const moved = '# Schema\n\nIndex.\n\n<a id="class-b"></a>\n\n## B\n\n<a id="class-c"></a>\n\n## C\n';
  const patch = sections.update(moved, first.rev);
  assert.deepEqual(patch.keys, ["", "class-b", "class-c"]);
  // `class-a` is gone, the index is the same, and `class-b` now ends with the blank line before
  // `class-c`.
  assert.equal(patch.revs[0], 1);
  assert.deepEqual([...patch.html.keys()], ["class-b", "class-c"]);
});

test("a key stays unique if an anchor is repeated", () => {
  const patch = new PageSections().update('<a id="x"></a>\n\nA\n\n<a id="x"></a>\n\nB\n', 0);
  assert.equal(patch.keys.length, 2);
  assert.equal(new Set(patch.keys).size, 2);
});

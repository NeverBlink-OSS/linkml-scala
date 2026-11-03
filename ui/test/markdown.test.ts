// Tests for markdown.ts. Run with `npm test` (see run.mjs).
import { test } from "node:test";
import assert from "node:assert/strict";
import { addWordBreaks, renderMarkdown } from "../src/markdown.js";

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

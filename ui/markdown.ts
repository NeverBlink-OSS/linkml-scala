// Turns Markdown into HTML for the playground's page view. Bundled into dist/markdown by build.mjs
// and imported by app.ts on first use only, like Mermaid.
import DOMPurify from "dompurify";
import { Marked } from "marked";

// GitHub-flavoured Markdown, so tables and fenced code read as they do on GitHub.
const marked = new Marked({ gfm: true });

// Every link that doesn't go to a section of the page opens in a new tab, so the playground keeps
// the schema being edited. This includes relative links in descriptions, such as `spec.md`, which
// would otherwise replace the playground. Links to sections start with `#`, and app.ts scrolls to
// them.
DOMPurify.addHook("afterSanitizeAttributes", (node) => {
  const href = node.tagName === "A" ? node.getAttribute("href") : null;
  if (href !== null && !href.startsWith("#")) {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener");
  }
});

/** The HTML for `markdown`, with anything that could run code removed.
 *
 * Schema descriptions are copied into the documentation as they are, and Markdown allows raw HTML.
 * A shared playground link can carry any schema, so a description could hold a `<script>` or an
 * `onerror` attribute. DOMPurify keeps only plain markup, as GitHub does. */
export function renderMarkdown(markdown: string): string {
  const html = marked.parse(markdown, { async: false });
  return DOMPurify.sanitize(html);
}

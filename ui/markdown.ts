// Turns Markdown into HTML for the playground's page view. Bundled into dist/markdown by build.mjs
// and imported by app.ts on first use only, like Mermaid.
import DOMPurify from "dompurify";
import { Marked } from "marked";

// GitHub-flavoured Markdown, so tables and fenced code read as they do on GitHub.
const marked = new Marked({ gfm: true });

/** The HTML for `markdown`, with anything that could run code removed.
 *
 * Schema descriptions are copied into the documentation as they are, and Markdown allows raw HTML.
 * A shared playground link can carry any schema, so a description could hold a `<script>` or an
 * `onerror` attribute. DOMPurify keeps only plain markup, as GitHub does. */
export function renderMarkdown(markdown: string): string {
  const html = marked.parse(markdown, { async: false });
  return DOMPurify.sanitize(html);
}

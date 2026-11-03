// Checks the HTML of the playground's page view, made by markdown.ts in the generation worker.
// Bundled into dist/sanitize by build.mjs and imported by app.ts on first use only, like Mermaid.
import DOMPurify from "dompurify";

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

/** The page for `html`, with anything that could run code removed.
 *
 * Schema descriptions are copied into the documentation as they are, and Markdown allows raw HTML.
 * A shared playground link can carry any schema, so a description could hold a `<script>` or an
 * `onerror` attribute. DOMPurify keeps only plain markup, as GitHub does.
 *
 * DOMPurify returns the nodes it has checked, rather than turning them back into HTML, which the
 * page would then have to parse a second time. */
export function sanitize(html: string): DocumentFragment {
  return DOMPurify.sanitize(html, { RETURN_DOM_FRAGMENT: true });
}

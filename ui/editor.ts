// CodeMirror 6 setup for the playground. Colors are driven entirely by the app's
// CSS variables (var(--…)), so the editors re-theme automatically when the app
// toggles light/dark – no reconfiguration needed.
import { Compartment, EditorState } from "@codemirror/state";
import {
  EditorView,
  keymap,
  lineNumbers,
  highlightActiveLine,
  highlightActiveLineGutter,
  drawSelection,
} from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import {
  HighlightStyle,
  StreamLanguage,
  type StreamParser,
  bracketMatching,
  indentOnInput,
  syntaxHighlighting,
} from "@codemirror/language";
import { yaml } from "@codemirror/lang-yaml";
import { json } from "@codemirror/lang-json";
import { turtle } from "@codemirror/legacy-modes/mode/turtle";
import { scala } from "@codemirror/legacy-modes/mode/clike";
import { tags as t } from "@lezer/highlight";

export type OutputLang = "json" | "yaml" | "turtle" | "scala" | "graphql" | "text";

// Minimal GraphQL SDL mode
const GRAPHQL_KEYWORDS = new Set([
  "type", "interface", "enum", "scalar", "input", "union", "schema", "directive",
  "extend", "implements", "on", "query", "mutation", "subscription", "fragment",
]);


interface GraphQLState {
  inBlockString: boolean; // Multi-line """ <text> """ descriptions
}

const graphql: StreamParser<GraphQLState> = {
  startState: () => ({ inBlockString: false }),
  copyState: (s) => ({ inBlockString: s.inBlockString }),
  token(stream, state) {
    if (state.inBlockString) {
      while (!stream.eol()) {
        if (stream.match('"""')) {
          state.inBlockString = false;
          break;
        }
        stream.next();
      }
      return "string";
    }
    if (stream.eatSpace()) return null;
    if (stream.match("#")) {
      stream.skipToEnd();
      return "comment";
    }
    if (stream.match('"""')) {
      while (!stream.eol()) {
        if (stream.match('"""')) return "string"; // closed on the same line
        stream.next();
      }
      state.inBlockString = true; // spans onto the next line
      return "string";
    }
    if (stream.match(/^"(?:[^"\\]|\\.)*"?/)) return "string";
    if (stream.match(/^@[A-Za-z_][A-Za-z0-9_]*/)) return "meta"; // @directive
    const word = stream.match(/^[A-Za-z_][A-Za-z0-9_]*/) as RegExpMatchArray | null;
    if (word) {
      const w = word[0];
      if (GRAPHQL_KEYWORDS.has(w)) return "keyword";
      if (w === "true" || w === "false" || w === "null") return "atom";
      // Capitalized identifiers read as type names; others as fields/args.
      return /^[A-Z]/.test(w) ? "type" : "variable";
    }
    if (stream.match(/^[[\]{}(),]/)) return "punctuation";
    if (stream.match(/^[:=!&|]/)) return "operator";
    stream.next();
    return null;
  },
};

const LANGS: Record<OutputLang, unknown[]> = {
  json: [json()],
  yaml: [yaml()],
  turtle: [StreamLanguage.define(turtle)],
  scala: [StreamLanguage.define(scala)],
  graphql: [StreamLanguage.define(graphql)],
  text: [],
};

const highlight = HighlightStyle.define([
  { tag: [t.definition(t.propertyName), t.propertyName], color: "var(--cyan)" },
  { tag: [t.keyword, t.moduleKeyword, t.modifier, t.self], color: "var(--blue)" },
  { tag: [t.typeName, t.className, t.namespace], color: "var(--blue)" },
  { tag: t.string, color: "var(--success)" },
  { tag: [t.number, t.integer, t.float], color: "var(--orange)" },
  { tag: [t.bool, t.null, t.atom, t.constant(t.name), t.keyword], color: "var(--purple)" },
  { tag: [t.comment, t.lineComment, t.blockComment], color: "var(--text-muted)", fontStyle: "italic" },
  { tag: [t.punctuation, t.separator, t.bracket, t.brace], color: "var(--text-muted)" },
  { tag: t.operator, color: "var(--text-secondary)" },
  { tag: [t.meta, t.annotation], color: "var(--orange)" }, // @directives (GraphQL), @prefix (Turtle)
  { tag: t.variableName, color: "var(--text)" },
]);

const appTheme = EditorView.theme({
  "&": { color: "var(--text)", backgroundColor: "transparent", height: "100%" },
  "&.cm-focused": { outline: "none" },
  ".cm-scroller": { fontFamily: "var(--code-font)", fontSize: "13px", lineHeight: "1.6" },
  ".cm-content": { padding: "10px 0" },
  ".cm-gutters": { backgroundColor: "transparent", color: "var(--text-muted)", border: "none" },
  ".cm-activeLine": { backgroundColor: "var(--accent-soft)" },
  ".cm-activeLineGutter": { backgroundColor: "var(--accent-soft)", color: "var(--text-secondary)" },
  ".cm-lineNumbers .cm-gutterElement": { padding: "0 8px 0 12px" },
  "&.cm-focused .cm-cursor": { borderLeftColor: "var(--text)" },
  ".cm-selectionBackground, &.cm-focused .cm-selectionBackground, .cm-content ::selection": {
    backgroundColor: "var(--accent-soft)",
  },
});

const shared = [appTheme, syntaxHighlighting(highlight), EditorView.lineWrapping];

export function createInput(parent: HTMLElement, doc: string, onChange: (v: string) => void): EditorView {
  return new EditorView({
    parent,
    state: EditorState.create({
      doc,
      extensions: [
        lineNumbers(),
        highlightActiveLine(),
        highlightActiveLineGutter(),
        history(),
        drawSelection(),
        indentOnInput(),
        bracketMatching(),
        keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
        yaml(),
        ...shared,
        EditorView.updateListener.of((u) => {
          if (u.docChanged) onChange(u.state.doc.toString());
        }),
      ],
    }),
  });
}

const outputLang = new Compartment();

export function createOutput(parent: HTMLElement): EditorView {
  return new EditorView({
    parent,
    state: EditorState.create({
      doc: "",
      extensions: [
        lineNumbers(),
        EditorState.readOnly.of(true),
        EditorView.editable.of(false),
        outputLang.of([]),
        ...shared,
      ],
    }),
  });
}

export function setOutput(view: EditorView, text: string, lang: OutputLang): void {
  view.dispatch({
    changes: { from: 0, to: view.state.doc.length, insert: text },
    effects: outputLang.reconfigure(LANGS[lang] as never),
  });
}

export function getDoc(view: EditorView): string {
  return view.state.doc.toString();
}

export function setDoc(view: EditorView, text: string): void {
  view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } });
}

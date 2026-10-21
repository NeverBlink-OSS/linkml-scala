import { Ajv2020 } from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

/** A validator that knows every format the generated schemas use. */
export function newAjv(): Ajv2020 {
  const ajv = new Ajv2020();
  // The CommonJS plugin exposes its callable default under NodeNext.
  addFormats.default(ajv, ["uri", "date-time"]);
  // ajv-formats has no `iri`. RFC 3987 maps an IRI to a URI by percent-encoding its non-ASCII
  // characters, so an IRI is valid when that URI is.
  const isUri = ajv.compile({ type: "string", format: "uri" });
  ajv.addFormat("iri", (s: string) => {
    const uri = toUri(s);
    return uri !== undefined && isUri(uri);
  });
  return ajv;
}

/** The URI for an IRI, or undefined if the IRI has a character that is neither ASCII nor `ucschar`. */
function toUri(iri: string): string | undefined {
  let uri = "";
  for (const c of iri) {
    const cp = c.codePointAt(0)!;
    if (cp < 0x80) uri += c;
    else if (isUcsChar(cp)) uri += encodeURIComponent(c);
    else return undefined;
  }
  return uri;
}

/** True for a code point in RFC 3987's `ucschar`, which an IRI may carry unencoded. */
function isUcsChar(cp: number): boolean {
  return (cp >= 0xa0 && cp <= 0xd7ff) || (cp >= 0xf900 && cp <= 0xfdcf) ||
    (cp >= 0xfdf0 && cp <= 0xffef) ||
    // Planes 1 to 13, each without its last two code points, then most of plane 14.
    (cp >= 0x10000 && cp <= 0xdfffd && (cp & 0xffff) <= 0xfffd) ||
    (cp >= 0xe1000 && cp <= 0xefffd);
}

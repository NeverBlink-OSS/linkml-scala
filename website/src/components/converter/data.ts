/** The generators and importers, from src/generated/converters.json, which `./mill regenerate`
 * writes from model/converter. */
import data from '../../generated/converters.json';

export type Converter = (typeof data.functions)[number];
export type Attribute = (typeof data.classes)[keyof typeof data.classes]['attributes'][number] & {
  cli?: string | null;
  scala?: string;
};

export const converters: Converter[] = data.functions;
export const classes: Record<string, { description: string; attributes: Attribute[] }> = data.classes;
export const enums: Record<string, { description: string; values: { name: string; description: string }[] }> =
  data.enums;

export function converter(name: string): Converter {
  const found = converters.find((c) => c.name === name);
  if (!found) throw new Error(`No converter ${name} in src/generated/converters.json`);
  return found;
}

/** The URL of a converter's page. */
export function pageUrl(converter: Converter): string {
  return `${import.meta.env.BASE_URL.replace(/\/$/, '')}/${converter.page}/`;
}

/** The id of the heading of a class on a page. */
export function anchor(className: string): string {
  return className.toLowerCase();
}

const escape = (text: string) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/** A description from the schema as HTML: `code` and links. */
export function inline(text: string): string {
  return text
    .split(/(`[^`]+`)/)
    .map((part) =>
      part.startsWith('`') && part.endsWith('`') && part.length > 1
        ? `<code>${escape(part.slice(1, -1))}</code>`
        : escape(part).replace(/https?:\/\/[^\s)]+[^\s).,;]/g, (url) => `<a href="${url}">${url}</a>`),
    )
    .join('');
}

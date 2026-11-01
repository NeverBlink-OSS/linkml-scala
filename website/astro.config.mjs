// @ts-check
import fs from 'node:fs';
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';
import starlightLlmsTxt from 'starlight-llms-txt';

const repoUrl = 'https://github.com/NeverBlink-OSS/ohi';

const sidebar = [
  { label: 'Overview', link: '/' },
  {
    label: 'Generators',
    items: [
      'generators',
      { label: 'Schemas', items: ['generators/json-schema', 'generators/graphql', 'generators/frictionless', 'generators/shacl'] },
      { label: 'Ontologies', items: ['generators/owl', 'generators/rdfs', 'generators/ossie'] },
      { label: 'Code', items: ['generators/pydantic', 'generators/typescript', 'generators/scala'] },
      { label: 'Docs', items: ['generators/docs', 'generators/er-diagram'] },
      { label: 'Schema tools', items: ['generators/linkml', 'generators/stats', 'generators/translation'] },
      'langstring',
    ],
  },
  { label: 'Importers', items: ['importers', 'importers/owl', 'importers/ossie'] },
  { label: 'Schemas', items: ['curies', 'import-maps', 'slot-usage-inheritance'] },
  { label: 'Using ohi LinkML', items: ['python-bindings', 'verifying-downloads'] },
  { label: 'Reference', items: ['implementation-differences', 'benchmarks'] },
  { label: 'Playground', link: 'https://linkml.ohi.dev/playground/', attrs: { target: '_blank' } },
];

// Every page has to be in the sidebar, or it could only be found through search
const pagesDir = new URL('./src/content/docs/', import.meta.url);
const pages = fs
  .readdirSync(pagesDir, { recursive: true })
  .filter((file) => /\.mdx?$/.test(file))
  .map((file) => file.replace(/\.mdx?$/, '').replace(/\/index$/, ''));
const entries = (items) => items.flatMap((item) => (item.items ? entries(item.items) : [item]));
const inSidebar = new Set(['index', ...entries(sidebar)]);
const missing = pages.filter((page) => !inSidebar.has(page));
if (missing.length > 0) {
  throw new Error(`Add these pages to the sidebar in website/astro.config.mjs: ${missing.join(', ')}`);
}

// Every generator and importer needs a page, which shows its examples and options
const converters = JSON.parse(fs.readFileSync(new URL('./src/generated/converters.json', import.meta.url), 'utf8'));
const withoutPage = converters.functions.filter((f) => !pages.includes(f.page));
if (withoutPage.length > 0) {
  throw new Error(`Add a page for each of these: ${withoutPage.map((f) => `src/content/docs/${f.page}.mdx`).join(', ')}`);
}

/** Copies the playground's favicon and fonts into public/, so the docs look the same (see
 * src/styles/theme.css). */
const playgroundAssets = {
  name: 'playground-assets',
  hooks: {
    'astro:config:setup': () => {
      const ui = new URL('../ui/', import.meta.url);
      const pub = new URL('./public/', import.meta.url);
      fs.cpSync(new URL('favicon.svg', ui), new URL('favicon.svg', pub));
      fs.cpSync(new URL('fonts/', ui), new URL('fonts/', pub), { recursive: true });
    },
  },
};

export default defineConfig({
  site: 'https://linkml.ohi.dev',
  base: '/docs',
  trailingSlash: 'always',
  // Pages that moved
  redirects: {
    '/owl/': '/docs/generators/owl/',
    '/pydantic/': '/docs/generators/pydantic/',
    '/typescript/': '/docs/generators/typescript/',
    '/docs/': '/docs/generators/docs/',
    '/ossie-mapping/': '/docs/generators/ossie/',
  },
  integrations: [
    playgroundAssets,
    starlight({
      title: 'ohi LinkML',
      description: 'Documentation for ohi LinkML, a LinkML implementation for the JVM, browsers, Python and native code.',
      favicon: '/favicon.svg',
      customCss: ['./src/styles/theme.css'],
      components: {
        SiteTitle: './src/components/SiteTitle.astro',
        ThemeProvider: './src/components/ThemeProvider.astro',
        ThemeSelect: './src/components/ThemeSelect.astro',
      },
      social: [
        { icon: 'github', label: 'GitHub', href: repoUrl },
        { icon: 'discord', label: 'Discord', href: 'https://discord.gg/HGksVAJ6ss' },
      ],
      editLink: { baseUrl: `${repoUrl}/edit/main/website/` },
      sidebar,
      plugins: [starlightLinksValidator(), starlightLlmsTxt()],
    }),
  ],
});

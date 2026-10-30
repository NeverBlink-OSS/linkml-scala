// @ts-check
import fs from 'node:fs';
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';

const repoUrl = 'https://github.com/NeverBlink-OSS/linkml-scala';

const sidebar = [
  { label: 'Overview', link: '/' },
  { label: 'Generators and importers', items: ['owl', 'pydantic', 'typescript', 'ossie-mapping', 'docs', 'langstring'] },
  { label: 'Schemas', items: ['curies', 'import-maps', 'slot-usage-inheritance'] },
  { label: 'Using ohi LinkML', items: ['python-bindings', 'verifying-downloads'] },
  { label: 'Reference', items: ['implementation-differences', 'benchmarks'] },
  { label: 'Playground', link: 'https://linkml.ohi.dev/playground/', attrs: { target: '_blank' } },
];

// Every page has to be in the sidebar, or it could only be found through search
const pagesDir = new URL('./src/content/docs/', import.meta.url);
const pages = fs
  .readdirSync(pagesDir)
  .filter((file) => /\.mdx?$/.test(file))
  .map((file) => file.replace(/\.mdx?$/, ''));
const inSidebar = new Set(['index', ...sidebar.flatMap((group) => group.items ?? [])]);
const missing = pages.filter((page) => !inSidebar.has(page));
if (missing.length > 0) {
  throw new Error(`Add these pages to the sidebar in website/astro.config.mjs: ${missing.join(', ')}`);
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
      plugins: [starlightLinksValidator()],
    }),
  ],
});

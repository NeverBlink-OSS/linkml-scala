// @ts-check
import path from 'node:path';
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';
import { docsDir, docSlugs, syncDocs } from './sync-docs.mjs';

const repoUrl = 'https://github.com/NeverBlink-OSS/linkml-scala';

const sidebar = [
  { label: 'Overview', link: '/' },
  { label: 'Playground', link: 'https://linkml.neverblink.eu/playground/', attrs: { target: '_blank' } },
  { label: 'Generators and importers', items: ['owl', 'pydantic', 'typescript', 'ossie-mapping', 'docs'] },
  { label: 'Schemas', items: ['curies', 'import-maps', 'slot-usage-inheritance'] },
  { label: 'Using LinkML-Scala', items: ['python-bindings', 'verifying-downloads'] },
  { label: 'Reference', items: ['implementation-differences', 'benchmarks'] },
];

// Every page in docs/ has to be in the sidebar, or it could only be found through search
const inSidebar = new Set(['index', ...sidebar.flatMap((group) => group.items ?? [])]);
const missing = docSlugs().filter((slug) => !inSidebar.has(slug));
if (missing.length > 0) {
  throw new Error(`Add these pages to the sidebar in website/astro.config.mjs: ${missing.join(', ')}`);
}

/** Copies docs/ into the content folder before a build, and again on every change in dev. */
const docsSync = {
  name: 'docs-sync',
  hooks: {
    'astro:config:setup': () => syncDocs(),
    /** @param {{ server: import('vite').ViteDevServer }} options */
    'astro:server:setup': ({ server }) => {
      server.watcher.add(docsDir);
      server.watcher.on('all', (_event, file) => {
        if (file.startsWith(docsDir + path.sep)) {
          try {
            syncDocs();
          } catch (error) {
            console.error(error);
          }
        }
      });
    },
  },
};

export default defineConfig({
  site: 'https://linkml.neverblink.eu',
  base: '/docs',
  trailingSlash: 'always',
  integrations: [
    docsSync,
    starlight({
      title: 'LinkML-Scala',
      description: 'Documentation for LinkML-Scala, a fast LinkML implementation for the JVM, browsers and native code.',
      logo: { light: './src/assets/logo-light.svg', dark: './src/assets/logo-dark.svg' },
      favicon: '/favicon.svg',
      customCss: ['./src/styles/theme.css'],
      social: [
        { icon: 'github', label: 'GitHub', href: repoUrl },
        { icon: 'discord', label: 'Discord', href: 'https://discord.gg/HGksVAJ6ss' },
      ],
      // Each page sets its own editUrl to its file in docs/
      editLink: { baseUrl: `${repoUrl}/edit/main/docs/` },
      sidebar,
      plugins: [starlightLinksValidator()],
    }),
  ],
});

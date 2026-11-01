// @ts-check
import fs from 'node:fs';
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';
import starlightLlmsTxt from 'starlight-llms-txt';

const repoUrl = 'https://github.com/NeverBlink-OSS/ohi';

/** Copies the ohi.dev fonts into public/, so the docs are set like the rest of the site (see
 * src/styles/theme.css). */
const brandFonts = {
  name: 'brand-fonts',
  hooks: {
    'astro:config:setup': () => {
      const ui = new URL('../../ui/', import.meta.url);
      const pub = new URL('./public/', import.meta.url);
      fs.cpSync(new URL('fonts/', ui), new URL('fonts/', pub), { recursive: true });
    },
  },
};

export default defineConfig({
  site: 'https://ohi.dev',
  base: '/docs',
  trailingSlash: 'always',
  redirects: {
    '/search': '/docs/reference/sdks/#search',
    '/locks': '/docs/reference/sdks/#save-and-reload-file-selections',
    '/reference': '/docs/reference/sdks/#metadata',
  },
  integrations: [
    brandFonts,
    starlight({
      title: 'ohi.dev',
      description: 'Find, download and manage ontologies.',
      logo: { src: './src/assets/logo.svg' },
      favicon: '/favicon.svg',
      customCss: ['./src/styles/theme.css'],
      components: {
        ThemeSelect: './src/components/ThemeSelect.astro',
      },
      social: [
        { icon: 'github', label: 'GitHub', href: repoUrl },
        { icon: 'discord', label: 'Discord', href: 'https://discord.gg/HGksVAJ6ss' },
      ],
      editLink: { baseUrl: `${repoUrl}/edit/main/ohi/docs/` },
      sidebar: [
        { label: 'Welcome', link: '/' },
        {
          label: 'Getting started',
          items: ['getting-started/install', 'getting-started/first-ontology'],
        },
        {
          label: 'Guides',
          items: ['guides/search', 'guides/projects', 'guides/ai-agents'],
        },
        {
          label: 'Reference',
          items: [
            'reference/cli',
            'reference/sdks',
            'reference/project-files',
          ],
        },
      ],
      plugins: [starlightLinksValidator(), starlightLlmsTxt()],
    }),
  ],
});

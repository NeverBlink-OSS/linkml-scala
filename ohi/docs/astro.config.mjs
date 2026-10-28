// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';

const repoUrl = 'https://github.com/NeverBlink-OSS/linkml-scala';

export default defineConfig({
  site: 'https://ohi.dev',
  base: '/docs',
  trailingSlash: 'always',
  integrations: [
    starlight({
      title: 'ohi SDK',
      description: 'Search ohi.dev and download ontologies from Scala, Python and TypeScript.',
      logo: { src: './src/assets/logo.svg' },
      favicon: '/favicon.svg',
      customCss: ['./src/styles/theme.css'],
      social: [
        { icon: 'github', label: 'GitHub', href: repoUrl },
        { icon: 'discord', label: 'Discord', href: 'https://discord.gg/HGksVAJ6ss' },
      ],
      editLink: { baseUrl: `${repoUrl}/edit/main/ohi/docs/` },
      sidebar: [
        { label: 'Overview', link: '/' },
        { label: 'ohi.dev', link: 'https://ohi.dev', attrs: { target: '_blank' } },
        { label: 'Guides', items: ['search', 'locks'] },
        { label: 'Reference', items: ['reference'] },
      ],
      plugins: [starlightLinksValidator()],
    }),
  ],
});

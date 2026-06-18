import { defineConfig } from 'vitepress';

export default defineConfig({
  title: 'Panels Handbook',
  description: 'Panel operations reference',
  base: '/handbook/',
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/' },
      { text: 'API', link: '/api/' },
    ],
    sidebar: [
      {
        text: 'Introduction',
        link: '/guide/introduction',
      },
      {
        text: 'Configuration',
        link: '/reference/configuration',
      },
    ],
    search: {
      provider: 'local',
    },
  },
});

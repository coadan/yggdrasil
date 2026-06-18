export default {
  title: 'Panels Docs',
  url: 'https://docs.example.com',
  baseUrl: '/panels/',
  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
        },
      },
    ],
  ],
  themeConfig: {
    navbar: {
      items: [
        { to: '/docs/intro', label: 'Intro', position: 'left' },
        { href: 'https://example.com/support', label: 'Support' },
      ],
    },
  },
};

import nextra from 'nextra';

const withNextra = nextra({
  contentDirBasePath: '/handbook',
});

export default withNextra({
  i18n: {
    locales: ['en', 'de'],
    defaultLocale: 'en',
  },
});

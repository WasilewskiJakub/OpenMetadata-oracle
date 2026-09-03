import playwright from 'eslint-plugin-playwright';
import tseslint from 'typescript-eslint';

export default [
  {
    ignores: ['.tmp/**', 'coverage/**', 'dist/**', 'node_modules/**', 'playwright-report/**', 'test-results/**'],
  },
  {
    ...playwright.configs['flat/recommended'],
    files: ['e2e/**/*.ts', 'playwright.config.ts'],
    languageOptions: {
      parser: tseslint.parser,
    },
    rules: {
      ...playwright.configs['flat/recommended'].rules,
      'playwright/no-force-option': 'error',
      'playwright/no-networkidle': 'error',
      'playwright/no-page-pause': 'error',
      'playwright/no-skipped-test': 'error',
      'playwright/no-wait-for-selector': 'error',
      'playwright/no-wait-for-timeout': 'error',
      'playwright/prefer-web-first-assertions': 'error',
    },
  },
];

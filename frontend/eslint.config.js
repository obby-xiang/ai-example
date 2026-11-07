import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

const productionRules = {
  'no-restricted-syntax': ['error', {
    selector: 'ImportDeclaration > ImportNamespaceSpecifier',
    message: 'Enumerate named imports instead of using a namespace import.',
  }],
}

export default tseslint.config(
  {
    ignores: [
      'dist/**',
      'coverage/**',
      'node_modules/**',
      'playwright-report/**',
      'test-results/**',
    ],
  },
  ...pluginVue.configs['flat/base'],
  {
    files: ['src/**/*.ts'],
    languageOptions: {
      parser: tseslint.parser,
    },
    rules: productionRules,
  },
  {
    files: ['src/**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
      },
    },
    rules: productionRules,
  },
)

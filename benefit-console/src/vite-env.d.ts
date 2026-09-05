/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BENEFIT_API_BASE?: string
  readonly VITE_BENEFIT_API_TARGET?: string
  readonly VITE_AUTH_MODE?: 'dev' | 'oidc'
  readonly VITE_CASDOOR_SERVER_URL?: string
  readonly VITE_CASDOOR_CLIENT_ID?: string
  readonly VITE_CASDOOR_ORGANIZATION?: string
  readonly VITE_CASDOOR_APP_NAME?: string
  readonly VITE_CASDOOR_SCOPE?: string
  readonly VITE_WORKFLOW_CONSOLE_ORIGIN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

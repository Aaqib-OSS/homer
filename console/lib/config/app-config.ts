import defaultConfig from '@/config/default.json';
import type { ThemeModes } from '@/lib/themes/loader';

export type AuthMode = 'cookie' | 'bearer' | 'disabled';

export interface AppConfig {
  api: {
    baseUrl: string;
    timeout: number;
    /** `CONSOLE_ALLOWED_API_ORIGINS`: empty is unrestricted; else login endpoints and CSP `connect-src` are pinned to it. */
    allowedOrigins: string[];
  };
  auth: {
    enabled: boolean;
    /** `cookie`: `HttpOnly` session cookie; `bearer`: `Authorization: Bearer` from `localStorage`; `disabled`: no auth. */
    mode: AuthMode;
    /** `CONSOLE_ALLOW_BEARER`: offers token sign-in; off by default as a `localStorage` token is XSS-exfiltratable (ADR-0011). */
    allowBearer: boolean;
    tokenKey: string;
  };
  polling: {
    processListInterval: number;
    processDetailActiveInterval: number;
    processDetailInactiveInterval: number;
    agentsCacheTime: number;
  };
  pagination: {
    defaultLimit: number;
    maxLimit: number;
  };
  ui: {
    defaultTheme: string;
  };
  plugins: {
    /** Raw `CONSOLE_PLUGINS` entries, each code run in the console's origin and so as trusted as the console (ADR-0059). */
    entries: string[];
  };
  themes: {
    /** Raw `CONSOLE_THEMES` entries (`name:/same-origin.css`); a stylesheet can redress the UI, so operator-trusted (ADR-0069). */
    entries: string[];
    /** The validated `CONSOLE_THEME_MODES` pair; when set, the switcher offers only it plus `System` (ADR-0069). */
    modes?: ThemeModes;
  };
}

interface RuntimeConfig {
  apiBaseUrl: string | null;
  authEnabled: boolean;
  authMode?: AuthMode;
  allowBearer?: boolean;
  allowedApiOrigins?: string[];
  pluginEntries?: string[];
  themeEntries?: string[];
  themeModes?: ThemeModes;
}

function normalizeMode(raw: string | undefined | null): AuthMode | null {
  if (raw === 'cookie' || raw === 'bearer' || raw === 'disabled') return raw;
  return null;
}

// Overlays the injected runtime config on the defaults; an absent key keeps the default.
function applyRuntimeConfig(config: AppConfig, rc: RuntimeConfig): void {
  if (rc.apiBaseUrl) config.api.baseUrl = rc.apiBaseUrl;
  applyRuntimeAuth(config, rc);
  if (rc.allowedApiOrigins) config.api.allowedOrigins = rc.allowedApiOrigins;
  if (rc.pluginEntries) config.plugins.entries = rc.pluginEntries;
  if (rc.themeEntries) config.themes.entries = rc.themeEntries;
  if (rc.themeModes) config.themes.modes = rc.themeModes;
}

function applyRuntimeAuth(config: AppConfig, rc: RuntimeConfig): void {
  config.auth.enabled = rc.authEnabled;
  const mode = normalizeMode(rc.authMode);
  if (mode) config.auth.mode = mode;
  if (rc.allowBearer !== undefined) config.auth.allowBearer = rc.allowBearer;
}

function getConfig(): AppConfig {
  const config: AppConfig = JSON.parse(JSON.stringify(defaultConfig));

  // An operator-replaced `default.json` may lack `themes`; without this `.entries` throws.
  config.themes ??= { entries: [] };

  if (typeof window !== 'undefined') {
    // Injected by the root layout: a browser bundle never sees `CONSOLE_*` env vars.
    const rc = (window as Window & { __HOMER_CONFIG__?: RuntimeConfig }).__HOMER_CONFIG__;
    if (rc) applyRuntimeConfig(config, rc);
  }

  return config;
}

export const appConfig = getConfig();

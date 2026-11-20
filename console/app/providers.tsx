'use client';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider, useTheme } from 'next-themes';
import { AuthProvider } from '@/lib/auth/auth-context';
import { useEffect, useMemo, useState } from 'react';
import {
  BUILT_IN_THEME_NAMES,
  missingThemeStylesheets,
  resolveThemeEntries,
  type ThemeEntry,
  type ThemeModes,
} from '@/lib/themes/loader';

/**
 * App-wide providers. `themeEntries` and `themeModes` are resolved by the root
 * layout and arrive as props, never read from `appConfig`, so server and client
 * agree on the theme list; `nonce` is the request's, for the pre-paint script (ADR-0069).
 */
export function Providers({
  children,
  themeEntries,
  themeModes,
  nonce,
}: {
  children: React.ReactNode;
  themeEntries: readonly string[];
  themeModes?: ThemeModes | null;
  nonce?: string;
}) {
  const [queryClient] = useState(createQueryClient);

  const themes = useMemo(() => resolveThemeEntries(themeEntries).entries, [themeEntries]);
  useMissingStylesheetReport(themes);
  const { themeNames, value } = themeProviderLists(themes, themeModes);

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider
        attribute="class"
        defaultTheme={themeModes ? 'system' : 'dark'}
        enableSystem
        themes={themeNames}
        value={value}
        nonce={nonce}
      >
        {themeModes && <HiddenThemeFallback />}
        <AuthProvider>{children}</AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: false,
        retry: 1,
      },
    },
  });
}

// Reports a missing theme sheet once the window has loaded; at mount a healthy sheet may
// still be in flight (ADR-0069).
function useMissingStylesheetReport(themes: ThemeEntry[]) {
  useEffect(() => {
    const report = () => {
      const missing = missingThemeStylesheets(
        themes,
        Array.from(document.styleSheets)
          .map((sheet) => sheet.href)
          .filter((href): href is string => href !== null),
      );
      for (const path of missing) {
        console.error(`[themes] stylesheet ${path} did not load; the theme it defines is unused`);
      }
    };
    if (document.readyState === 'complete') {
      report();
      return;
    }
    window.addEventListener('load', report, { once: true });
    return () => window.removeEventListener('load', report);
  }, [themes]);
}

// With a valid pair only the configured names are listed and `system` maps onto the pair (ADR-0069).
function themeProviderLists(themes: ThemeEntry[], themeModes: ThemeModes | null | undefined) {
  const customNames = themes.map(({ name }) => name);
  const themeNames = themeModes ? customNames : [...BUILT_IN_THEME_NAMES, ...customNames];
  const value = themeModes
    ? Object.fromEntries([
        ...BUILT_IN_THEME_NAMES.map((name) => [name, name]),
        ...customNames.map((name) => [name, name]),
        ['system', 'system'],
        ['light', themeModes.light],
        ['dark', themeModes.dark],
      ])
    : undefined;
  return { themeNames, value };
}

/**
 * Resets a stored built-in (a class the pre-paint script applies before React
 * runs and `next-themes` never validates) to `system` once. Why: ADR-0069,
 * Amendment.
 */
function HiddenThemeFallback() {
  const { theme, setTheme } = useTheme();
  useEffect(() => {
    if (!theme || theme === 'system') return;
    if ((BUILT_IN_THEME_NAMES as readonly string[]).includes(theme)) setTheme('system');
  }, [theme, setTheme]);
  return null;
}

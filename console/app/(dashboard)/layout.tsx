'use client';

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth/auth-context';
import { loginHref } from '@/lib/auth/return-path';
import { appConfig } from '@/lib/config/app-config';
import { Header } from '@/components/layout/header';
import { MaintenanceBanner } from '@/components/layout/maintenance-banner';
import { Sidebar } from '@/components/layout/sidebar';
import { VersionBadge } from '@/components/layout/version-badge';
import { useAgentReloadStream, useProcessStatusStream } from '@/lib/api/streams';
import { useAuthExpiredHandler } from '@/lib/auth/use-auth-expired-handler';
import { useMarkInAppNavigation } from '@/lib/hooks/use-mark-in-app-navigation';
import { PluginsProvider } from '@/lib/plugins/plugins-context';

export const dynamic = 'force-dynamic';

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { status } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  // A session-less first visit must not read as an expiry; the handler latches `authed` from status.
  const expiredHandled = useAuthExpiredHandler(status);

  useEffect(() => {
    // Redirect only on explicit anon, so the auth probe lands before we bounce.
    // Skip once expiry recovery replaced to /login: a second push would drop the expired banner.
    if (status === 'anon' && appConfig.auth.enabled && !expiredHandled.current) {
      router.push(loginHref(pathname, false));
    }
  }, [status, router, pathname, expiredHandled]);

  useProcessStatusStream();
  useAgentReloadStream();
  useMarkInAppNavigation();

  if (status === 'loading') {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="text-muted-foreground">Loading...</div>
      </div>
    );
  }

  if (status === 'anon' && appConfig.auth.enabled) {
    return null;
  }

  return (
    <PluginsProvider>
      <div className="flex min-h-screen flex-col">
        <MaintenanceBanner />
        <Header />
        <div className="flex flex-1">
          <Sidebar />
          <main id="main" className="flex-1 overflow-auto">
            {children}
          </main>
        </div>
        <VersionBadge />
      </div>
    </PluginsProvider>
  );
}

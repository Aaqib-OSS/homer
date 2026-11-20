'use client';

import { AlertTriangle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { useBoundaryError, type ErrorBoundaryProps } from '@/lib/hooks/use-boundary-error';

/**
 * Route-segment error boundary for every page under `(dashboard)`.
 *
 * Replaces the page's content when a child throws during render; the dashboard
 * layout stays mounted. Must be a Client Component.
 */
export default function DashboardError({ error, reset }: ErrorBoundaryProps) {
  const details = useBoundaryError(error);

  return (
    <div className="p-6">
      <Card className="mx-auto max-w-xl border-destructive">
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-destructive">
            <AlertTriangle className="size-5" />
            Something went wrong
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <p className="text-sm text-muted-foreground">
            This page hit an unexpected error. The rest of the console is still usable.
          </p>
          <pre className="overflow-auto rounded-md bg-muted p-3 text-xs text-foreground">
            {details}
          </pre>
          <Button onClick={reset}>Try again</Button>
        </CardContent>
      </Card>
    </div>
  );
}

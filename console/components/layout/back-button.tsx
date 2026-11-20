'use client';

import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { ArrowLeft } from 'lucide-react';
import { IN_APP_NAV_KEY } from '@/lib/hooks/use-mark-in-app-navigation';

// Going back keeps the list page's URL params, so use history only when the app navigated here.
// `history.length` cannot tell that from external history; sessionStorage tracks it.
function hasInAppHistory() {
  try {
    return sessionStorage.getItem(IN_APP_NAV_KEY) === '1';
  } catch {
    return false;
  }
}

export function BackButton({ fallbackHref, label }: { fallbackHref: string; label: string }) {
  const router = useRouter();
  const handleClick = () => {
    if (hasInAppHistory()) router.back();
    else router.push(fallbackHref);
  };
  return (
    <Button
      variant="ghost"
      size="sm"
      onClick={handleClick}
      className="text-muted-foreground hover:text-foreground -ml-2"
    >
      <ArrowLeft className="h-4 w-4 mr-1" aria-hidden="true" />
      {label}
    </Button>
  );
}

'use client';

import { useRouter } from 'next/navigation';
import { Check, Plus, Server, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useInstances } from '@/lib/instances/use-instances';
import { useAuth } from '@/lib/auth/auth-context';
import type { Instance } from '@/lib/instances/store';

/** One instance row: selects it, and removes it unless it is the last one. */
function InstanceRow({
  instance,
  isActive,
  canRemove,
  onSelect,
  onRemove,
}: {
  instance: Instance;
  isActive: boolean;
  canRemove: boolean;
  onSelect: () => void;
  onRemove: () => void;
}) {
  return (
    <DropdownMenuItem role="menuitemradio" aria-checked={isActive} onSelect={onSelect}>
      <span className="flex min-w-0 flex-col">
        <span className="truncate">{instance.label}</span>
        <span className="truncate text-xs text-muted-foreground">{instance.baseUrl}</span>
      </span>
      <span className="ml-auto flex items-center gap-1">
        {isActive && <Check className="h-4 w-4" aria-hidden data-testid="active-check" />}
        {canRemove && (
          <button
            type="button"
            className="rounded-sm p-0.5 text-muted-foreground hover:text-destructive"
            aria-label={`Remove ${instance.label}`}
            onClick={(event) => {
              // Radix selects the row unless the click is defaulted-out here.
              event.preventDefault();
              event.stopPropagation();
              onRemove();
            }}
          >
            <Trash2 className="h-3.5 w-3.5" aria-hidden />
          </button>
        )}
      </span>
    </DropdownMenuItem>
  );
}

/**
 * Picks the active Homer instance, removes a saved one, or routes to login to add one.
 * Switching resets the query cache and re-probes auth (`useAuth().switchInstance`), then
 * lands on /processes so no stale detail route remains.
 */
export function InstanceSwitcher() {
  const { instances, activeInstanceId, removeInstance } = useInstances();
  const { switchInstance, signOutAndSwitch } = useAuth();
  const router = useRouter();
  const active = instances.find((i) => i.id === activeInstanceId);
  const canRemove = instances.length > 1;

  function select(id: string) {
    if (id === activeInstanceId) return;
    switchInstance(id);
    router.push('/processes');
  }

  // Removing the active instance signs out of it while it is still active and
  // switches in one step — no transient anon status for the auth gate to see.
  async function handleRemove(id: string) {
    if (id !== activeInstanceId) {
      removeInstance(id);
      return;
    }
    const next = instances.find((i) => i.id !== id);
    if (!next) return;
    await signOutAndSwitch(next.id);
    removeInstance(id);
    router.push('/processes');
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm" className="gap-2" aria-label="Select instance">
          <Server className="h-4 w-4" aria-hidden />
          <span className="max-w-[12rem] truncate">{active?.label ?? 'Instance'}</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56">
        <DropdownMenuLabel>Instance</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {instances.map((inst) => (
          <InstanceRow
            key={inst.id}
            instance={inst}
            isActive={inst.id === activeInstanceId}
            canRemove={canRemove}
            onSelect={() => select(inst.id)}
            onRemove={() => handleRemove(inst.id)}
          />
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => router.push('/login')}>
          <Plus className="h-4 w-4" aria-hidden />
          <span>Add instance</span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

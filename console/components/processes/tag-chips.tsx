'use client';

import Link from 'next/link';
import { Tag } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils/cn';

// A chip's face: the tag icon and the visually truncated tag.
function TagLabel({ tag }: { tag: string }) {
  return (
    <span className="inline-flex max-w-[16rem] items-center gap-1">
      <Tag className="h-3 w-3 shrink-0" aria-hidden="true" />
      <span className="truncate">{tag}</span>
    </span>
  );
}

// A chip that hands its tag to `onTagClick` on click, Enter or Space.
function ButtonChip({ tag, onTagClick }: { tag: string; onTagClick: (tag: string) => void }) {
  return (
    <Badge
      variant="secondary"
      className="cursor-pointer font-mono font-normal"
      title={tag}
      role="button"
      tabIndex={0}
      aria-label={`Filter by tag ${tag}`}
      onClick={(e) => {
        // Chips can live inside a clickable table row — don't let the
        // click bubble up and navigate to the process detail instead.
        e.stopPropagation();
        onTagClick(tag);
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          e.stopPropagation();
          onTagClick(tag);
        }
      }}
    >
      <TagLabel tag={tag} />
    </Badge>
  );
}

// A chip that links to the process list filtered to its tag.
function LinkChip({ tag }: { tag: string }) {
  return (
    <Link
      href={`/processes?tag=${encodeURIComponent(tag)}`}
      title={tag}
      aria-label={`Filter by tag ${tag}`}
      onClick={(e) => e.stopPropagation()}
    >
      <Badge variant="secondary" className="cursor-pointer font-mono font-normal">
        <TagLabel tag={tag} />
      </Badge>
    </Link>
  );
}

/**
 * Renders a run's subject tags (`key:value`) as chips linking to `/processes?tag=<tag>`, or
 * calling `onTagClick` instead (the list page ANDs the tag into its filter set). Long tags
 * truncate visually only: the title tooltip and the link or callback carry the full string.
 */
export function TagChips({
  tags,
  onTagClick,
  className,
}: {
  tags: string[];
  onTagClick?: (tag: string) => void;
  className?: string;
}) {
  if (tags.length === 0) return null;

  return (
    <div className={cn('flex flex-wrap items-center gap-1', className)}>
      {tags.map((tag) =>
        onTagClick ? (
          <ButtonChip key={tag} tag={tag} onTagClick={onTagClick} />
        ) : (
          <LinkChip key={tag} tag={tag} />
        ),
      )}
    </div>
  );
}

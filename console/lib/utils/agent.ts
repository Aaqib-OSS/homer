import type { AgentInfo, AgentInput, AgentParameterTransformer } from '@/types/homer';

/**
 * Returns the declared inputs for an agent: `inputs`, else the `queryParams` alias.
 * Callers use this instead of reading either field, so the alias can go in one place.
 */
export function getAgentInputs(
  agent:
    | Pick<AgentInfo, 'inputs' | 'queryParams'>
    | { inputs?: AgentInput[] | null; queryParams?: AgentInput[] | null }
): AgentInput[] {
  return agent.inputs ?? agent.queryParams ?? [];
}

/**
 * Returns a human-readable description of a transformer
 */
export function getTransformerDescription(
  transformer: AgentParameterTransformer
): string {
  switch (transformer.type) {
    case 'pass':
      return 'Direct pass-through';
    case 'jsonPath':
      return `Extract JSON path: ${transformer.path || 'unknown'}`;
    default:
      return 'Unknown transformer';
  }
}

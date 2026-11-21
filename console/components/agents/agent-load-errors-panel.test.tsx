import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { AgentLoadErrorsPanel, AgentLoadErrorsSection } from './agent-load-errors-panel';

const useAgentLoadErrorsMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({ useAgentLoadErrors: useAgentLoadErrorsMock }));

describe('AgentLoadErrorsPanel', () => {
  it('counts the failed agents and lists every message', () => {
    render(
      <AgentLoadErrorsPanel
        loadErrors={{ errors: ['broken: bad.yaml', 'refused: @SYSENV:JAVA_OPTS'], truncated: 0 }}
        onDismiss={vi.fn()}
      />,
    );

    expect(screen.getByText('2 agents failed to load')).toBeInTheDocument();
    expect(screen.getByText('broken: bad.yaml')).toBeInTheDocument();
    expect(screen.getByText('refused: @SYSENV:JAVA_OPTS')).toBeInTheDocument();
    expect(screen.queryByText(/more not shown/)).toBeNull();
  });

  it('uses the singular for one failed agent', () => {
    render(<AgentLoadErrorsPanel loadErrors={{ errors: ['broken'], truncated: 0 }} onDismiss={vi.fn()} />);

    expect(screen.getByText('1 agent failed to load')).toBeInTheDocument();
  });

  it('reports the messages the server did not keep', () => {
    render(
      <AgentLoadErrorsPanel loadErrors={{ errors: ['broken'], truncated: 42 }} onDismiss={vi.fn()} />,
    );

    expect(screen.getByText('+42 more not shown')).toBeInTheDocument();
  });

  it('renders nothing when the last scan found no error', () => {
    const { container } = render(
      <AgentLoadErrorsPanel loadErrors={{ errors: [], truncated: 0 }} onDismiss={vi.fn()} />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('dismisses through the panel button', async () => {
    const onDismiss = vi.fn();
    const user = userEvent.setup();
    render(<AgentLoadErrorsPanel loadErrors={{ errors: ['broken'], truncated: 0 }} onDismiss={onDismiss} />);

    await user.click(screen.getByRole('button', { name: 'Dismiss agent load errors' }));

    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it('renders a message as text, never as markup', () => {
    render(
      <AgentLoadErrorsPanel
        loadErrors={{ errors: ['<img src=x onerror="alert(1)">'], truncated: 0 }}
        onDismiss={vi.fn()}
      />,
    );

    expect(screen.getByText('<img src=x onerror="alert(1)">')).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
  });

  it('has no accessibility violations', async () => {
    const { container } = render(
      <AgentLoadErrorsPanel loadErrors={{ errors: ['broken: bad.yaml'], truncated: 3 }} onDismiss={vi.fn()} />,
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('AgentLoadErrorsSection', () => {
  beforeEach(() => {
    useAgentLoadErrorsMock.mockReturnValue({ data: { errors: ['broken'], truncated: 0 } });
  });

  it('renders the panel for an admin and hides it once dismissed', async () => {
    const user = userEvent.setup();
    render(<AgentLoadErrorsSection isAdmin scanToken={0} />);

    await user.click(screen.getByRole('button', { name: 'Dismiss agent load errors' }));

    expect(screen.queryByText('1 agent failed to load')).toBeNull();
  });

  it('renders nothing for a non-admin, who never asks the server', () => {
    const { container } = render(<AgentLoadErrorsSection isAdmin={false} scanToken={0} />);

    expect(container).toBeEmptyDOMElement();
    expect(useAgentLoadErrorsMock).toHaveBeenCalledWith(false);
  });

  it('shows the panel again when a reload changes the scan token', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<AgentLoadErrorsSection isAdmin scanToken={0} />);
    await user.click(screen.getByRole('button', { name: 'Dismiss agent load errors' }));

    rerender(<AgentLoadErrorsSection isAdmin scanToken={1} />);

    expect(screen.getByText('1 agent failed to load')).toBeInTheDocument();
  });
});

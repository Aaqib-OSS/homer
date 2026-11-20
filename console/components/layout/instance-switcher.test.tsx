import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const calls: string[] = [];
const switchInstance = vi.fn();
const signOutAndSwitch = vi.fn();
const logout = vi.fn();
const removeInstance = vi.fn();
const push = vi.fn();
const useInstancesMock = vi.hoisted(() => vi.fn());

vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('@/lib/auth/auth-context', () => ({
  useAuth: () => ({ switchInstance, signOutAndSwitch, logout }),
}));
vi.mock('@/lib/instances/use-instances', () => ({ useInstances: () => useInstancesMock() }));

import { InstanceSwitcher } from './instance-switcher';

const instances = [
  { id: 'default', label: 'Default', baseUrl: 'http://localhost:8080', mode: 'cookie' },
  { id: 'remote', label: 'Remote', baseUrl: 'https://b.example.com', mode: 'bearer' },
];

describe('InstanceSwitcher', () => {
  beforeEach(() => {
    calls.length = 0;
    switchInstance.mockReset().mockImplementation(() => {
      calls.push('switchInstance');
    });
    logout.mockReset().mockImplementation(async () => {
      calls.push('logout');
    });
    signOutAndSwitch.mockReset().mockImplementation(async () => {
      calls.push('signOutAndSwitch');
    });
    removeInstance.mockReset().mockImplementation(() => {
      calls.push('removeInstance');
    });
    push.mockReset();
    useInstancesMock.mockReturnValue({ instances, activeInstanceId: 'default', removeInstance });
  });

  it('shows the active instance label on the trigger', () => {
    render(<InstanceSwitcher />);
    expect(screen.getByRole('button', { name: /select instance/i })).toHaveTextContent('Default');
  });

  it('lists instances and marks the active one', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    expect(screen.getByRole('menuitemradio', { name: /Default/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.getByRole('menuitemradio', { name: /Remote/ })).toHaveAttribute(
      'aria-checked',
      'false',
    );
  });

  it('renders the check mark only inside the active row', async () => {
    // The icon is aria-hidden — no role query reaches it, so assert it in the
    // row's DOM or it could vanish (or appear everywhere) with the suite green.
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    const rows = screen.getAllByRole('menuitemradio');
    const activeRow = rows.find((row) => row.getAttribute('aria-checked') === 'true');
    expect(activeRow).toBeDefined();
    expect(within(activeRow!).getByTestId('active-check')).toBeInTheDocument();
    for (const row of rows.filter((row) => row !== activeRow)) {
      expect(within(row).queryByTestId('active-check')).not.toBeInTheDocument();
    }
  });

  it('switches and routes to /processes when picking another instance', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    await user.click(screen.getByRole('menuitemradio', { name: /Remote/ }));
    expect(switchInstance).toHaveBeenCalledWith('remote');
    expect(push).toHaveBeenCalledWith('/processes');
  });

  it('does not switch when picking the already-active instance', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    await user.click(screen.getByRole('menuitemradio', { name: /Default/ }));
    expect(switchInstance).not.toHaveBeenCalled();
  });

  it('routes to /login to add an instance', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    await user.click(screen.getByRole('menuitem', { name: /add instance/i }));
    expect(push).toHaveBeenCalledWith('/login');
  });

  it('offers a remove action on every row while more than one instance remains', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    expect(screen.getByRole('button', { name: 'Remove Default' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove Remote' })).toBeInTheDocument();
  });

  it('hides the remove action on the last remaining instance', async () => {
    useInstancesMock.mockReturnValue({
      instances: [instances[0]],
      activeInstanceId: 'default',
      removeInstance,
    });
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    expect(screen.queryByRole('button', { name: /^remove /i })).not.toBeInTheDocument();
  });

  it('removes a non-active instance without switching to it or signing out', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    await user.click(screen.getByRole('button', { name: 'Remove Remote' }));
    expect(removeInstance).toHaveBeenCalledWith('remote');
    expect(switchInstance).not.toHaveBeenCalled();
    expect(signOutAndSwitch).not.toHaveBeenCalled();
    expect(logout).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });

  it('suppresses the remove click so the row is not selected', async () => {
    // The handler must default the click out (Radix reads it as selection) and
    // stop the bubble — assert both directly on the dispatched event.
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    const bubbled = vi.fn();
    document.addEventListener('click', bubbled);
    try {
      const event = new MouseEvent('click', { bubbles: true, cancelable: true });
      fireEvent(screen.getByRole('button', { name: 'Remove Remote' }), event);
      expect(event.defaultPrevented).toBe(true);
      expect(bubbled).not.toHaveBeenCalled();
    } finally {
      document.removeEventListener('click', bubbled);
    }
  });

  it('signs out of a removed active instance, switches to a remaining one and routes', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    await user.click(screen.getByRole('button', { name: 'Remove Default' }));
    await waitFor(() => expect(push).toHaveBeenCalledWith('/processes'));
    // The combined helper signs out and switches without ever publishing a
    // transient anon status the dashboard gate would redirect on.
    expect(calls).toEqual(['signOutAndSwitch', 'removeInstance']);
    expect(signOutAndSwitch).toHaveBeenCalledWith('remote');
    expect(logout).not.toHaveBeenCalled();
    expect(switchInstance).not.toHaveBeenCalled();
    expect(removeInstance).toHaveBeenCalledWith('default');
  });
});

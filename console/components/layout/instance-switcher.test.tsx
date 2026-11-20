import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const calls: string[] = [];
const switchInstance = vi.fn();
const logout = vi.fn();
const removeInstance = vi.fn();
const push = vi.fn();
const useInstancesMock = vi.hoisted(() => vi.fn());

vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('@/lib/auth/auth-context', () => ({ useAuth: () => ({ switchInstance, logout }) }));
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
    expect(logout).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });

  it('signs out of a removed active instance, switches to a remaining one and routes', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    await user.click(screen.getByRole('button', { name: 'Remove Default' }));
    await waitFor(() => expect(push).toHaveBeenCalledWith('/processes'));
    expect(calls).toEqual(['logout', 'switchInstance', 'removeInstance']);
    expect(switchInstance).toHaveBeenCalledWith('remote');
    expect(removeInstance).toHaveBeenCalledWith('default');
  });
});

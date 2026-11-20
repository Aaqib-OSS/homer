import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useEffect } from 'react';
import { appConfig } from '@/lib/config/app-config';
import {
  DEFAULT_INSTANCE_ID,
  resetInstancesStoreForTests,
  setActiveInstance,
  upsertInstance,
  type Instance,
} from './store';
import { useActiveInstanceId, useInstances } from './use-instances';

const remote: Instance = {
  id: 'remote',
  label: 'Remote',
  baseUrl: 'https://homer-b.example.com',
  mode: 'bearer',
};

describe('useInstances', () => {
  beforeEach(() => {
    localStorage.clear();
    resetInstancesStoreForTests();
  });

  it('exposes the seeded default instance on an empty store', () => {
    const { result } = renderHook(() => useInstances());
    expect(result.current.instances).toHaveLength(1);
    expect(result.current.instances[0].id).toBe(DEFAULT_INSTANCE_ID);
    expect(result.current.instances[0].baseUrl).toBe(appConfig.api.baseUrl);
  });

  it('exposes the seeded default as the active instance', () => {
    const { result } = renderHook(() => useInstances());
    expect(result.current.activeInstanceId).toBe(DEFAULT_INSTANCE_ID);
  });

  it('returns the store mutations by reference', () => {
    const { result } = renderHook(() => useInstances());
    expect(result.current.setActiveInstance).toBe(setActiveInstance);
    expect(result.current.upsertInstance).toBe(upsertInstance);
  });

  it('re-renders the same mount with an upserted instance', () => {
    let mounts = 0;
    const { result } = renderHook(() => {
      useEffect(() => {
        mounts += 1;
      }, []);
      return useInstances();
    });

    act(() => upsertInstance(remote));

    expect(mounts).toBe(1);
    expect(result.current.instances).toHaveLength(2);
    expect(result.current.instances[1]).toEqual(remote);
  });

  it('re-renders useInstances when the active instance changes', () => {
    const { result } = renderHook(() => useInstances());

    act(() => upsertInstance(remote));
    act(() => setActiveInstance(remote.id));

    expect(result.current.activeInstanceId).toBe(remote.id);
  });

  it('re-renders useActiveInstanceId when the active instance changes', () => {
    const { result } = renderHook(() => useActiveInstanceId());

    act(() => upsertInstance(remote));
    act(() => setActiveInstance(remote.id));

    expect(result.current).toBe(remote.id);
  });
});

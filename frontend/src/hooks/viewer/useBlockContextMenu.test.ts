import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useBlockContextMenu } from './useBlockContextMenu';

describe('useBlockContextMenu', () => {
  it('returns a stable handler that prevents the default action', () => {
    const { result } = renderHook(() => useBlockContextMenu());
    let prevented = false;
    result.current({ preventDefault: () => (prevented = true) });
    expect(prevented).toBe(true);
  });
});

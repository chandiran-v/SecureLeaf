import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { loadRazorpay, RAZORPAY_SCRIPT_URL, resetRazorpayLoaderForTests, type RazorpayConstructor } from './razorpay';

const scriptTags = () => document.head.querySelectorAll(`script[src="${RAZORPAY_SCRIPT_URL}"]`);

describe('loadRazorpay (Phase 09B D3)', () => {
  beforeEach(() => {
    resetRazorpayLoaderForTests();
    delete window.Razorpay;
    document.head.innerHTML = '';
  });
  afterEach(() => {
    delete window.Razorpay;
  });

  it('appends the checkout.js script ONCE however many times it is asked for', async () => {
    const first = loadRazorpay();
    const second = loadRazorpay();
    expect(scriptTags()).toHaveLength(1);

    // Play the browser: the script runs (defining window.Razorpay) and fires onload.
    const fake = class {} as unknown as RazorpayConstructor;
    window.Razorpay = fake;
    (scriptTags()[0] as HTMLScriptElement).onload?.(new Event('load'));

    await expect(first).resolves.toBe(fake);
    await expect(second).resolves.toBe(fake);
    expect(scriptTags()).toHaveLength(1);
  });

  it('reuses window.Razorpay without adding a script when it is already there', async () => {
    const fake = class {} as unknown as RazorpayConstructor;
    window.Razorpay = fake;

    await expect(loadRazorpay()).resolves.toBe(fake);
    expect(scriptTags()).toHaveLength(0);
  });

  it('rejects when the script cannot load, and allows a retry afterwards', async () => {
    const failed = loadRazorpay();
    (scriptTags()[0] as HTMLScriptElement).onerror?.(new Event('error'));
    await expect(failed).rejects.toThrow(/could not load/i);

    const retry = loadRazorpay();           // a fresh attempt, not the cached rejection
    expect(scriptTags()).toHaveLength(1);
    window.Razorpay = class {} as unknown as RazorpayConstructor;
    (scriptTags()[0] as HTMLScriptElement).onload?.(new Event('load'));
    await expect(retry).resolves.toBeTypeOf('function');
  });
});

import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import StatusBadge from './StatusBadge';

describe('StatusBadge', () => {
  it('renders correctly for LIVE status', () => {
    render(<StatusBadge status="LIVE" />);
    const badge = screen.getByTestId('status-badge-live');
    expect(badge).toHaveTextContent('Live');
    expect(badge).toHaveClass('bg-emerald-50');
  });

  it('renders correctly for PROCESSING status with pulse animation', () => {
    render(<StatusBadge status="PROCESSING" />);
    const badge = screen.getByTestId('status-badge-processing');
    expect(badge).toHaveTextContent('Processing');
    // Ensure the dot has animate-pulse
    const dot = badge.querySelector('.animate-pulse');
    expect(dot).toBeInTheDocument();
  });

  it('renders correctly for DRAFT status', () => {
    render(<StatusBadge status="DRAFT" />);
    const badge = screen.getByTestId('status-badge-draft');
    expect(badge).toHaveTextContent('Draft');
    expect(badge).toHaveClass('bg-gray-100');
  });

  // Phase 6, D1 — the library reuses this same component for entitlement statuses.
  it('renders correctly for ACTIVE (entitlement) status', () => {
    render(<StatusBadge status="ACTIVE" />);
    const badge = screen.getByTestId('status-badge-active');
    expect(badge).toHaveTextContent('Active');
    expect(badge).toHaveClass('bg-emerald-50');
  });

  it('renders correctly for REVOKED (entitlement) status', () => {
    render(<StatusBadge status="REVOKED" />);
    const badge = screen.getByTestId('status-badge-revoked');
    expect(badge).toHaveTextContent('Revoked');
    expect(badge).toHaveClass('bg-red-50');
  });

  it('renders correctly for EXPIRED (entitlement) status', () => {
    render(<StatusBadge status="EXPIRED" />);
    const badge = screen.getByTestId('status-badge-expired');
    expect(badge).toHaveTextContent('Expired');
    expect(badge).toHaveClass('bg-slate-100');
  });
});

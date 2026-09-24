import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import UploadProductPage from './UploadProductPage';
import { useCreatorProducts } from '../../hooks/useCreatorProducts';
import { useAuth } from '../../hooks/useAuth';

// Mock dependencies
vi.mock('../../hooks/useAuth');
vi.mock('../../hooks/useCreatorProducts');
vi.mock('../../lib/restClient', () => ({
  default: { post: vi.fn() },
}));

describe('UploadProductPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(useAuth).mockReturnValue({
      user: { id: '1', displayName: 'Creator' },
      logout: vi.fn(),
    } as any);
    vi.mocked(useCreatorProducts).mockReturnValue({
      createProduct: vi.fn().mockResolvedValue({ id: 'prod-123' }),
      createLoading: false,
    } as any);
  });

  const renderComponent = () => {
    render(
      <MemoryRouter>
        <UploadProductPage />
      </MemoryRouter>
    );
  };

  it('renders step 1 (form) initially', () => {
    renderComponent();
    expect(screen.getByRole('heading', { name: 'Product details' })).toBeInTheDocument();
    expect(screen.getByLabelText(/Title \*/i)).toBeInTheDocument();
  });

  it('moves to step 2 after successful product creation', async () => {
    renderComponent();
    
    // Fill required fields
    fireEvent.change(screen.getByLabelText(/Title \*/i), { target: { value: 'Test PDF' } });
    fireEvent.change(screen.getByLabelText(/Description \*/i), { target: { value: 'Desc' } });
    
    // Submit form
    fireEvent.click(screen.getByRole('button', { name: /Continue/i }));
    
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Upload PDF' })).toBeInTheDocument();
    });
  });

  it('validates file type client-side in step 2', async () => {
    renderComponent();
    
    // Quick advance to step 2 by mocking the state
    fireEvent.change(screen.getByLabelText(/Title \*/i), { target: { value: 'Test PDF' } });
    fireEvent.change(screen.getByLabelText(/Description \*/i), { target: { value: 'Desc' } });
    fireEvent.click(screen.getByRole('button', { name: /Continue/i }));
    
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Upload PDF' })).toBeInTheDocument();
    });

    const fileInput = document.getElementById('pdf-file-input') as HTMLInputElement;
    expect(fileInput).toBeInTheDocument();

    // Mock a non-PDF file
    const file = new File(['dummy content'], 'test.txt', { type: 'text/plain' });
    fireEvent.change(fileInput, { target: { files: [file] } });

    await waitFor(() => {
      expect(screen.getByText('Only PDF files are accepted.')).toBeInTheDocument();
    });
  });

  it('validates file size client-side in step 2', async () => {
    renderComponent();
    
    fireEvent.change(screen.getByLabelText(/Title \*/i), { target: { value: 'Test PDF' } });
    fireEvent.change(screen.getByLabelText(/Description \*/i), { target: { value: 'Desc' } });
    fireEvent.click(screen.getByRole('button', { name: /Continue/i }));
    
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Upload PDF' })).toBeInTheDocument();
    });

    const fileInput = document.getElementById('pdf-file-input') as HTMLInputElement;

    // Mock a huge PDF (51MB)
    const file = new File(['dummy'], 'huge.pdf', { type: 'application/pdf' });
    Object.defineProperty(file, 'size', { value: 51 * 1024 * 1024 });

    fireEvent.change(fileInput, { target: { files: [file] } });

    await waitFor(() => {
      expect(screen.getByText(/File is too large/i)).toBeInTheDocument();
    });
  });
});

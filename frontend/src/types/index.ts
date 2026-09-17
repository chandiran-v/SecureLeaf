// Global TypeScript type definitions for SecureLeaf frontend
// Add shared domain types here; feature-specific types live alongside their modules.

export type UUID = string;

// ── Auth & Users ─────────────────────────────────────────────────────────────

export type UserRole = 'BUYER' | 'CREATOR' | 'ADMIN';

export interface User {
  id: UUID;
  email: string;
  displayName: string;
  roles: UserRole[];
  createdAt: string; // ISO 8601
}

export interface AuthPayload {
  accessToken: string;
  refreshToken: string;
  user: User;
}

// ── Products ──────────────────────────────────────────────────────────────────

export type ProductStatus = 'PROCESSING' | 'LIVE' | 'UNPUBLISHED' | 'FAILED';

export interface Category {
  id: UUID;
  name: string;
  slug: string;
}

export interface Product {
  id: UUID;
  title: string;
  description: string;
  price: number;
  status: ProductStatus;
  creator: User;
  category: Category;
  tags: string[];
  thumbnailUrl?: string;
  averageRating?: number;
  totalSales: number;
  freePreviewPages: number;
  createdAt: string;
}

export interface ProductPage {
  content: Product[];
  totalElements: number;
  totalPages: number;
  pageNumber: number;
}

// ── Commerce ──────────────────────────────────────────────────────────────────

export type OrderStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'REFUNDED';

export interface Entitlement {
  id: UUID;
  product: Product;
  purchasedAt: string;
  isActive: boolean;
}

// ── DRM Viewer ────────────────────────────────────────────────────────────────

export interface ViewerSession {
  productId: UUID;
  totalPages: number;
  currentPage: number;
}

// ── Reviews ───────────────────────────────────────────────────────────────────

export interface Review {
  id: UUID;
  buyer: User;
  rating: number;
  comment?: string;
  createdAt: string;
}

// ── Notifications ─────────────────────────────────────────────────────────────

export interface Notification {
  id: UUID;
  title: string;
  message: string;
  isRead: boolean;
  createdAt: string;
}

// ── Filters & Pagination ──────────────────────────────────────────────────────

export interface ProductFilterInput {
  categorySlug?: string;
  minPrice?: number;
  maxPrice?: number;
  isFree?: boolean;
  searchQuery?: string;
  sortBy?: 'newest' | 'popular' | 'rating' | 'price_asc' | 'price_desc';
}

export interface PaginationParams {
  page?: number;
  size?: number;
}

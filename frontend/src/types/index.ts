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

export type ProductStatus = 'DRAFT' | 'PROCESSING' | 'LIVE' | 'UNPUBLISHED' | 'FAILED';

export interface Category {
  id: UUID;
  name: string;
  slug: string;
}

// Public-safe projection of a product's creator — never carries email.
// Matches the backend's CreatorSummary GraphQL type (see D4, phase-3 design doc).
export interface CreatorSummary {
  id: UUID;
  displayName: string;
}

export interface Product {
  id: UUID;
  title: string;
  description: string;
  pricePaise: number;  // always in paise (₹1 = 100 paise) — never floats for money
  status: ProductStatus;
  creator: CreatorSummary;
  category: Category;
  tags: string[];
  thumbnailUrl?: string;
  averageRating?: number;
  totalSales: number;
  freePreviewPages: number;
  pageCount?: number;
  createdAt: string;
  // Only selected by queries that need it (product detail). Always false for anonymous visitors.
  ownedByMe?: boolean;
}

export interface ProductPage {
  content: Product[];
  totalElements: number;
  totalPages: number;
  pageNumber: number;
}

// ── Commerce ──────────────────────────────────────────────────────────────────

export type OrderStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'REFUNDED';
export type EntitlementStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED';

export interface Order {
  id: UUID;
  status: OrderStatus;
  totalAmountPaise: number;
  product: Product;
  gatewayOrderId?: string | null;  // Razorpay "order_XXXX"; null for free products
  failureReason?: string | null;   // latest declined attempt
  createdAt: string;
}

export interface InitiateOrderPayload {
  order: Order;
  gatewayOrderId: string | null;   // null → free product, already COMPLETED
  gatewayKeyId: string | null;     // public Razorpay key id — never a secret
  currency: string;
}

// Exactly what Razorpay's checkout.js hands its success `handler` (snake_case is Razorpay's).
export interface GatewaySuccessResponse {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
}

export interface VerifyPaymentInput {
  orderId: UUID;
  gatewayOrderId: string;
  gatewayPaymentId: string;
  gatewaySignature: string;
}

export interface Entitlement {
  id: UUID;
  product: Product;
  purchasedAt: string;
  status: EntitlementStatus;
}

// Amounts arrive as the GraphQL `Long` scalar → JSON numbers. Safe in JS up to 2^53 paise
// (≈ ₹90 lakh crore), far beyond anything we'll see.
export interface CreatorEarnings {
  salesCount: number;
  grossSalesPaise: number;
  platformFeePaise: number;
  netEarningsPaise: number;
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

export type NotificationType =
  | 'PURCHASE_SUCCESS'
  | 'SALE_RECEIVED'
  | 'PROCESSING_COMPLETE'
  | 'PROCESSING_FAILED'
  | 'PAYOUT_STATUS_UPDATE';

export interface Notification {
  id: UUID;
  type: NotificationType;
  title: string;
  body?: string | null;
  isRead: boolean;
  createdAt: string;
}

// ── Filters & Pagination ──────────────────────────────────────────────────────

export type ProductSortBy = 'newest' | 'popular' | 'rating' | 'price_asc' | 'price_desc';

export interface ProductFilterInput {
  categorySlug?: string;
  minPricePaise?: number;
  maxPricePaise?: number;
  isFree?: boolean;
  searchQuery?: string;
  sortBy?: ProductSortBy;
}

export interface PaginationParams {
  page?: number;
  size?: number;
}

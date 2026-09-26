// Global TypeScript type definitions for SecureLeaf frontend
// Add shared domain types here; feature-specific types live alongside their modules.

export type UUID = string;

// ── Auth & Users ─────────────────────────────────────────────────────────────

export type UserRole = 'BUYER' | 'CREATOR' | 'ADMIN';

// Mirrors the backend's AccountStatus/AuthProvider enums (Phase 8, D3) — admin-only fields,
// never exposed on the plain `User` type (see AdminUser below).
export type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED';
export type AuthProviderType = 'LOCAL' | 'GOOGLE';

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

// Mirrors the backend's JobStage enum (Phase 6, D4) — the processing pipeline's 5 stages.
export type JobStage = 'VALIDATE' | 'CONVERT_TILES' | 'GENERATE_THUMBNAIL' | 'GENERATE_PREVIEW' | 'MARK_LIVE';

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
  // Phase 6, D2 — creator-dashboard-only. Null for anyone but the product's own creator (the
  // server enforces this; the client never has to). Only requested by the dashboard's query.
  salesCount?: number | null;
  netEarningsPaise?: number | null;
  // Phase 6, D4 — non-null only while status is PROCESSING (processingStage) or FAILED (failureReason).
  processingStage?: JobStage | null;
  failureReason?: string | null;
  // Phase 8, D5 — set only when an admin's takeDownProduct moved this product to
  // UNPUBLISHED; null for a creator's own unpublishProduct. The creator dashboard uses
  // this to show why and to hide the Republish button.
  takedownReason?: string | null;
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

// ── DRM Viewer (Phase 05A backend; wired into a page by Phase 05B) ─────────────
// Session lifecycle is GraphQL (types below); tile bytes are REST — CLAUDE.md's
// "GraphQL for data, REST for files" rule. See docs/phases/phase-05a-secure-viewer-backend.md.

export type ViewerSessionStatus = 'ACTIVE' | 'SUPERSEDED' | 'EXPIRED';

export interface ViewerSession {
  sessionId: UUID;
  // Returned ONCE, here, at startViewerSession — only its SHA-256 hash is ever stored
  // server-side (same reasoning as a refresh token). Losing this means losing the session.
  sessionToken: string;
  productId: UUID;
  pageCount?: number | null;
  heartbeatIntervalSeconds: number;
  expiresAt: string; // ISO 8601 — current lease expiry
}

// The client's every-`heartbeatIntervalSeconds` lease renewal (viewerHeartbeat mutation).
export interface ViewerHeartbeat {
  status: ViewerSessionStatus;
  expiresAt: string;
}

// A single-use, ~30s link to one watermarked tile: GET the `url` over REST, not GraphQL.
export interface SignedPageUrl {
  url: string;
  expiresAt: string;
}

// ── Reviews (REV-01..04) ────────────────────────────────────────────────────

// Public-safe projection of a review's author — mirrors the backend's ReviewerSummary
// GraphQL type (D5). Deliberately just a name: the original Review.buyer: User! field
// leaked the reviewer's email to anyone who could see the review.
export interface ReviewerSummary {
  displayName: string;
}

export interface Review {
  id: UUID;
  reviewer: ReviewerSummary;
  rating: number;
  reviewText?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ReviewPage {
  content: Review[];
  totalElements: number;
  totalPages: number;
  pageNumber: number;
}

// One star value's count for the rating histogram (D6) — always all five, zero included.
export interface RatingCount {
  rating: number;
  count: number;
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
  // MARKET-03 — only products whose averageRating >= this value; unrated products never match.
  minRating?: number;
}

export interface PaginationParams {
  page?: number;
  size?: number;
}

// ── Admin (Phase 8: ADMIN-01..04, AUTH-08) ────────────────────────────────────

export interface AdminUser {
  id: UUID;
  email: string;
  displayName: string;
  roles: UserRole[];
  accountStatus: AccountStatus;
  authProvider: AuthProviderType;
  createdAt: string;
  productCount: number;
  purchaseCount: number;
}

export interface AdminUserPage {
  content: AdminUser[];
  totalElements: number;
  totalPages: number;
  pageNumber: number;
}

export interface AdminUserFilterInput {
  search?: string;
  role?: UserRole;
  status?: AccountStatus;
}

export interface AdminProduct {
  id: UUID;
  title: string;
  status: ProductStatus;
  creator: CreatorSummary;
  pricePaise: number;
  totalSales: number;
  takenDownAt?: string | null;
  takedownReason?: string | null;
  createdAt: string;
}

export interface AdminProductPage {
  content: AdminProduct[];
  totalElements: number;
  totalPages: number;
  pageNumber: number;
}

export interface AdminProductFilterInput {
  search?: string;
  status?: ProductStatus;
  creatorId?: string;
}

// One row of PlatformStats.topProducts — top 5 by sales in the selected window.
export interface TopProduct {
  productId: UUID;
  title: string;
  salesCount: number;
  grossSalesPaise: number;
}

export interface PlatformStats {
  totalUsers: number;
  totalCreators: number;
  totalLiveProducts: number;
  completedOrdersAllTime: number;
  grossSalesPaiseAllTime: number;
  platformFeePaiseAllTime: number;
  windowDays: number;
  completedOrdersWindow: number;
  grossSalesPaiseWindow: number;
  platformFeePaiseWindow: number;
  topProducts: TopProduct[];
}

// Public-safe projection of the acting admin — same reasoning as CreatorSummary.
export interface AdminActor {
  id: UUID;
  displayName: string;
}

export interface AdminAction {
  id: UUID;
  admin: AdminActor;
  action: string;
  targetType: string;
  targetId?: UUID | null;
  reason?: string | null;
  createdAt: string;
}

export interface AdminActionPage {
  content: AdminAction[];
  totalElements: number;
  totalPages: number;
  pageNumber: number;
}

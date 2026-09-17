-- SecureLeaf - V1 Initial Schema (Updated)

-- ENUM Types
CREATE TYPE auth_provider AS ENUM ('LOCAL', 'GOOGLE');
CREATE TYPE account_status AS ENUM ('ACTIVE', 'SUSPENDED', 'DEACTIVATED');
CREATE TYPE user_role AS ENUM ('BUYER', 'CREATOR', 'ADMIN');
CREATE TYPE product_status AS ENUM ('DRAFT', 'PROCESSING', 'LIVE', 'UNPUBLISHED', 'FAILED');
CREATE TYPE job_status AS ENUM ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED');
CREATE TYPE job_stage AS ENUM ('VALIDATE', 'CONVERT_TILES', 'GENERATE_THUMBNAIL', 'GENERATE_PREVIEW', 'MARK_LIVE');
CREATE TYPE order_status AS ENUM ('PENDING', 'COMPLETED', 'FAILED', 'REFUNDED');
CREATE TYPE payment_status AS ENUM ('PENDING', 'COMPLETED', 'FAILED', 'REFUNDED');
CREATE TYPE entitlement_status AS ENUM ('ACTIVE', 'REVOKED', 'EXPIRED');
CREATE TYPE payout_status AS ENUM ('REQUESTED', 'APPROVED', 'PROCESSING', 'PAID', 'REJECTED');
CREATE TYPE notification_type AS ENUM ('PURCHASE_SUCCESS', 'SALE_RECEIVED', 'PROCESSING_COMPLETE', 'PROCESSING_FAILED', 'PAYOUT_STATUS_UPDATE');

-- Identity & Access
CREATE TABLE users (
    id                     BIGSERIAL      PRIMARY KEY,
    email                  VARCHAR(255)   NOT NULL,
    password_hash          VARCHAR(255),
    auth_provider          auth_provider  NOT NULL DEFAULT 'LOCAL',
    google_sub             VARCHAR(255),
    display_name           VARCHAR(100)   NOT NULL,
    avatar_url             VARCHAR(500),
    account_status         account_status NOT NULL DEFAULT 'ACTIVE',
    reset_token_hash       VARCHAR(255),
    reset_token_expires_at TIMESTAMPTZ,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    deleted_at             TIMESTAMPTZ,
    CONSTRAINT uq_users_email           UNIQUE (email),
    CONSTRAINT uq_users_google_sub      UNIQUE (google_sub),
    CONSTRAINT chk_users_local_has_pwd  CHECK (auth_provider != 'LOCAL' OR password_hash IS NOT NULL),
    CONSTRAINT chk_users_google_has_sub CHECK (auth_provider != 'GOOGLE' OR google_sub IS NOT NULL)
);
-- Note: PostgreSQL automatically creates indexes for UNIQUE constraints (email, google_sub).
CREATE INDEX idx_users_account_status ON users (account_status);
CREATE INDEX idx_users_deleted_at     ON users (deleted_at)       WHERE deleted_at IS NOT NULL;

CREATE TABLE creator_profiles (
    user_id       BIGINT       PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    bio           TEXT,
    payout_email  VARCHAR(255),
    payout_upi    VARCHAR(100),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE user_roles (
    user_id     BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role        user_role NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, role)
);
CREATE INDEX idx_user_roles_user_id ON user_roles (user_id);

CREATE TABLE refresh_tokens (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash     VARCHAR(255) NOT NULL,
    device_hint    VARCHAR(255),
    issued_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at     TIMESTAMPTZ  NOT NULL,
    revoked_at     TIMESTAMPTZ,
    replaced_by_id BIGINT       REFERENCES refresh_tokens (id),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_user_id    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- Product & Content Lifecycle
CREATE TABLE categories (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_categories_name UNIQUE (name),
    CONSTRAINT uq_categories_slug UNIQUE (slug)
);

CREATE TABLE products (
    id                 BIGSERIAL      PRIMARY KEY,
    creator_id         BIGINT         NOT NULL REFERENCES users (id),
    category_id        BIGINT         NOT NULL REFERENCES categories (id),
    title              VARCHAR(255)   NOT NULL,
    slug               VARCHAR(300)   NOT NULL,
    description        TEXT           NOT NULL,
    cover_image_url    VARCHAR(500),
    price_paise        INTEGER        NOT NULL DEFAULT 0 CHECK (price_paise >= 0),
    free_preview_pages INTEGER        NOT NULL DEFAULT 3 CHECK (free_preview_pages >= 0),
    status             product_status NOT NULL DEFAULT 'DRAFT',
    total_sales        INTEGER        NOT NULL DEFAULT 0 CHECK (total_sales >= 0),
    average_rating     NUMERIC(3,2)   CHECK (average_rating BETWEEN 1.00 AND 5.00),
    review_count       INTEGER        NOT NULL DEFAULT 0 CHECK (review_count >= 0),
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT uq_products_slug UNIQUE (slug)
);
CREATE INDEX idx_products_creator_id  ON products (creator_id);
CREATE INDEX idx_products_category_id ON products (category_id);
CREATE INDEX idx_products_status      ON products (status);
CREATE INDEX idx_products_deleted_at  ON products (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_products_live_listing ON products (status, created_at DESC) WHERE status = 'LIVE' AND deleted_at IS NULL;
CREATE INDEX idx_products_fts ON products USING GIN (to_tsvector('english', title || ' ' || description));

CREATE TABLE product_tags (
    product_id BIGINT      NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    tag        VARCHAR(50) NOT NULL CHECK (tag = LOWER(TRIM(tag))),
    PRIMARY KEY (product_id, tag)
);
CREATE INDEX idx_product_tags_tag        ON product_tags (tag);
CREATE INDEX idx_product_tags_product_id ON product_tags (product_id);

CREATE TABLE document_versions (
    id                   BIGSERIAL    PRIMARY KEY,
    product_id           BIGINT       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    version_number       INTEGER      NOT NULL DEFAULT 1 CHECK (version_number >= 1),
    original_filename    VARCHAR(255) NOT NULL,
    file_size_bytes      BIGINT       NOT NULL CHECK (file_size_bytes > 0),
    mime_type            VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    raw_minio_bucket     VARCHAR(100) NOT NULL,
    raw_minio_object_key VARCHAR(500) NOT NULL,
    page_count           INTEGER      CHECK (page_count > 0),
    thumbnail_minio_key  VARCHAR(500),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at         TIMESTAMPTZ,
    CONSTRAINT uq_document_versions_product_version UNIQUE (product_id, version_number)
);
CREATE INDEX idx_document_versions_product_id ON document_versions (product_id);

CREATE TABLE content_pages (
    id                  BIGSERIAL    PRIMARY KEY,
    document_version_id BIGINT       NOT NULL REFERENCES document_versions (id) ON DELETE CASCADE,
    page_number         INTEGER      NOT NULL CHECK (page_number >= 1),
    bucket_name         VARCHAR(100) NOT NULL,
    minio_object_key    VARCHAR(500) NOT NULL,
    width_px            INTEGER      CHECK (width_px > 0),
    height_px           INTEGER      CHECK (height_px > 0),
    file_size_bytes     BIGINT       CHECK (file_size_bytes > 0),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_content_pages_version_page UNIQUE (document_version_id, page_number)
);
CREATE INDEX idx_content_pages_document_version_id ON content_pages (document_version_id);

-- Commerce & Entitlements
CREATE TABLE orders (
    id           BIGSERIAL    PRIMARY KEY,
    buyer_id     BIGINT       NOT NULL REFERENCES users (id),
    total_amount_paise INTEGER NOT NULL CHECK (total_amount_paise >= 0),
    status       order_status NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_orders_buyer_id   ON orders (buyer_id);
CREATE INDEX idx_orders_status     ON orders (status);

CREATE TABLE order_items (
    id           BIGSERIAL    PRIMARY KEY,
    order_id     BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id   BIGINT       NOT NULL REFERENCES products (id),
    price_paise  INTEGER      NOT NULL CHECK (price_paise >= 0),
    CONSTRAINT uq_order_items_order_product UNIQUE (order_id, product_id)
);
CREATE INDEX idx_order_items_order_id ON order_items (order_id);

CREATE TABLE payments (
    id                   BIGSERIAL      PRIMARY KEY,
    order_id             BIGINT         NOT NULL REFERENCES orders (id),
    idempotency_key      VARCHAR(255)   NOT NULL,
    provider_payment_id  VARCHAR(255),
    provider_name        VARCHAR(50)    NOT NULL DEFAULT 'MOCK',
    amount_paise         INTEGER        NOT NULL CHECK (amount_paise >= 0),
    status               payment_status NOT NULL DEFAULT 'PENDING',
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_payments_order_id        UNIQUE (order_id),
    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key)
);
CREATE INDEX idx_payments_provider_payment_id ON payments (provider_payment_id) WHERE provider_payment_id IS NOT NULL;

CREATE TABLE payment_events (
    id                BIGSERIAL      PRIMARY KEY,
    payment_id        BIGINT         NOT NULL REFERENCES payments (id),
    from_status       payment_status,
    to_status         payment_status NOT NULL,
    event_source      VARCHAR(100)   NOT NULL,
    provider_event_id VARCHAR(255),
    raw_payload       JSONB,
    occurred_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_payment_events_provider_event UNIQUE (provider_event_id)
);
CREATE INDEX idx_payment_events_payment_id     ON payment_events (payment_id);

CREATE TABLE entitlements (
    id                  BIGSERIAL          PRIMARY KEY,
    buyer_id            BIGINT             NOT NULL REFERENCES users (id),
    product_id          BIGINT             NOT NULL REFERENCES products (id),
    document_version_id BIGINT             NOT NULL REFERENCES document_versions (id),
    order_id            BIGINT             NOT NULL REFERENCES orders (id),
    status              entitlement_status NOT NULL DEFAULT 'ACTIVE',
    granted_at          TIMESTAMPTZ        NOT NULL DEFAULT NOW(),
    revoked_at          TIMESTAMPTZ,
    revocation_reason   TEXT,
    CONSTRAINT uq_entitlements_order_product UNIQUE (order_id, product_id)
);
CREATE INDEX idx_entitlements_buyer_id            ON entitlements (buyer_id);
CREATE INDEX idx_entitlements_product_id          ON entitlements (product_id);
CREATE INDEX idx_entitlements_document_version_id ON entitlements (document_version_id);
CREATE INDEX idx_entitlements_order_id            ON entitlements (order_id);
CREATE INDEX idx_entitlements_buyer_product_active ON entitlements (buyer_id, product_id) WHERE status = 'ACTIVE';

-- DRM Viewer & Audit
CREATE TABLE viewer_sessions (
    id                 BIGSERIAL    PRIMARY KEY,
    user_id            BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    product_id         BIGINT       NOT NULL REFERENCES products (id),
    entitlement_id     BIGINT       NOT NULL REFERENCES entitlements (id),
    session_token_hash VARCHAR(255) NOT NULL,
    device_fingerprint VARCHAR(255),
    ip_address         INET,
    started_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_heartbeat_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ended_at           TIMESTAMPTZ,
    CONSTRAINT uq_viewer_sessions_token UNIQUE (session_token_hash)
);
CREATE INDEX idx_viewer_sessions_user_product ON viewer_sessions (user_id, product_id);
CREATE INDEX idx_viewer_sessions_last_heartbeat ON viewer_sessions (last_heartbeat_at);
CREATE INDEX idx_viewer_sessions_active ON viewer_sessions (user_id, product_id) WHERE ended_at IS NULL;

CREATE TABLE viewer_access_logs (
    id                  BIGSERIAL    PRIMARY KEY,
    viewer_session_id   BIGINT       NOT NULL REFERENCES viewer_sessions (id),
    user_id             BIGINT       NOT NULL REFERENCES users (id),
    product_id          BIGINT       NOT NULL REFERENCES products (id),
    document_version_id BIGINT       NOT NULL REFERENCES document_versions (id),
    content_page_id     BIGINT       NOT NULL REFERENCES content_pages (id),
    page_number         INTEGER      NOT NULL CHECK (page_number >= 1),
    ip_address          INET,
    user_agent          TEXT,
    correlation_id      VARCHAR(100),
    viewed_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_viewer_access_logs_user_id    ON viewer_access_logs (user_id);
CREATE INDEX idx_viewer_access_logs_product_id ON viewer_access_logs (product_id);
CREATE INDEX idx_viewer_access_logs_session_id ON viewer_access_logs (viewer_session_id);
CREATE INDEX idx_viewer_access_logs_viewed_at  ON viewer_access_logs (viewed_at);

-- Async Processing
CREATE TABLE processing_jobs (
    id                  BIGSERIAL    PRIMARY KEY,
    document_version_id BIGINT       NOT NULL REFERENCES document_versions (id) ON DELETE CASCADE,
    product_id          BIGINT       NOT NULL REFERENCES products (id),
    status              job_status   NOT NULL DEFAULT 'QUEUED',
    current_stage       job_stage,
    retry_count         INTEGER      NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    max_retries         INTEGER      NOT NULL DEFAULT 3,
    failure_reason      TEXT,
    worker_id           VARCHAR(100),
    claimed_at          TIMESTAMPTZ,
    queued_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_processing_jobs_status_queued ON processing_jobs (status, queued_at ASC) WHERE status = 'QUEUED';
CREATE INDEX idx_processing_jobs_document_version_id ON processing_jobs (document_version_id);
CREATE INDEX idx_processing_jobs_product_id          ON processing_jobs (product_id);

-- Engagement & Payouts
CREATE TABLE reviews (
    id          BIGSERIAL   PRIMARY KEY,
    product_id  BIGINT      NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    buyer_id    BIGINT      NOT NULL REFERENCES users (id),
    rating      SMALLINT    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    review_text TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_reviews_buyer_product UNIQUE (buyer_id, product_id)
);
CREATE INDEX idx_reviews_product_id ON reviews (product_id);
CREATE INDEX idx_reviews_buyer_id   ON reviews (buyer_id);

CREATE TABLE notifications (
    id                 BIGSERIAL         PRIMARY KEY,
    recipient_id       BIGINT            NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type               notification_type NOT NULL,
    title              VARCHAR(255)      NOT NULL,
    body               TEXT,
    related_product_id BIGINT            REFERENCES products (id) ON DELETE SET NULL,
    related_order_id   BIGINT            REFERENCES orders (id)   ON DELETE SET NULL,
    is_read            BOOLEAN           NOT NULL DEFAULT FALSE,
    read_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ       NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notifications_recipient_id ON notifications (recipient_id);
CREATE INDEX idx_notifications_recipient_unread ON notifications (recipient_id, created_at DESC) WHERE is_read = FALSE;

CREATE TABLE creator_payouts (
    id                   BIGSERIAL     PRIMARY KEY,
    creator_id           BIGINT        NOT NULL REFERENCES users (id),
    amount_paise         BIGINT        NOT NULL CHECK (amount_paise > 0),
    status               payout_status NOT NULL DEFAULT 'REQUESTED',
    gross_revenue_paise  BIGINT        NOT NULL,
    platform_fee_paise   BIGINT        NOT NULL,
    net_payout_paise     BIGINT        NOT NULL,
    payout_method        VARCHAR(50),
    payout_reference     VARCHAR(255),
    requested_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    processed_at         TIMESTAMPTZ,
    notes                TEXT,
    approved_by          BIGINT        REFERENCES users (id)
);
CREATE INDEX idx_creator_payouts_creator_id ON creator_payouts (creator_id);
CREATE INDEX idx_creator_payouts_status     ON creator_payouts (status);

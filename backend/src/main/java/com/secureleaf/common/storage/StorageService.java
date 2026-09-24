package com.secureleaf.common.storage;

import java.time.Duration;

/**
 * Abstraction over object storage (MinIO in dev/prod; in-memory in tests).
 *
 * Coded as an interface from day one so:
 * 1. The processing pipeline's compile-time surface never directly touches MinIO.
 * 2. Integration tests can inject an in-memory implementation — no MinIO container needed.
 * 3. A future swap to S3 or GCS touches only one implementation class.
 *
 * This is the Strategy pattern: the pipeline is the "context" that holds a
 * reference to a StorageService strategy, and the strategy can be swapped
 * without changing any pipeline logic.
 */
public interface StorageService {

    /**
     * Store bytes at the given key in the given bucket.
     *
     * @param bucket      the MinIO bucket name
     * @param key         the object key (path within the bucket)
     * @param content     the raw bytes to store
     * @param contentType the MIME type (e.g., "application/pdf", "image/png")
     */
    void put(String bucket, String key, byte[] content, String contentType);

    /**
     * Retrieve bytes from the given bucket and key.
     */
    byte[] get(String bucket, String key);

    /**
     * Generate a pre-signed URL that grants temporary read access to the object.
     * The URL expires after {@code ttl}; after that it returns 403.
     *
     * SECURITY: these are the only URLs ever given to a browser — and only
     * for watermarked tiles, never for raw PDFs or clean tiles.
     *
     * @param bucket the bucket
     * @param key    the object key
     * @param ttl    how long the URL should remain valid (e.g., Duration.ofSeconds(30))
     * @return a signed URL string
     */
    String presignedGetUrl(String bucket, String key, Duration ttl);

    /**
     * Delete an object. No-op if it doesn't exist.
     */
    void delete(String bucket, String key);
}

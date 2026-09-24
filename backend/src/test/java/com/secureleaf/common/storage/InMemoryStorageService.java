package com.secureleaf.common.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of StorageService for integration tests.
 *
 * Why @Primary?
 * In tests, this overrides MinioStorageService so we don't need a real
 * MinIO container running during the test phase. It stores files in a
 * ConcurrentHashMap which is automatically wiped between test suite runs.
 */
@Service
@Primary
@Slf4j
public class InMemoryStorageService implements StorageService {

    private final Map<String, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public void put(String bucket, String key, byte[] content, String contentType) {
        String fullKey = bucket + "/" + key;
        store.put(fullKey, content);
        log.debug("[InMemory] Stored {} bytes at {}", content.length, fullKey);
    }

    @Override
    public byte[] get(String bucket, String key) {
        String fullKey = bucket + "/" + key;
        byte[] content = store.get(fullKey);
        if (content == null) {
            throw new RuntimeException("Object not found: " + fullKey);
        }
        return content;
    }

    @Override
    public String presignedGetUrl(String bucket, String key, Duration ttl) {
        // Return a mock URL for testing
        return "http://localhost:8080/mock-minio/" + bucket + "/" + key;
    }

    @Override
    public void delete(String bucket, String key) {
        String fullKey = bucket + "/" + key;
        store.remove(fullKey);
        log.debug("[InMemory] Deleted {}", fullKey);
    }

    /** Helper for tests to verify what was stored */
    public boolean exists(String bucket, String key) {
        return store.containsKey(bucket + "/" + key);
    }

    /** Helper to clear the store between tests */
    public void clear() {
        store.clear();
    }
}

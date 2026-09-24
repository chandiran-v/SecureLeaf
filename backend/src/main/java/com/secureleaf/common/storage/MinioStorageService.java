package com.secureleaf.common.storage;

import com.secureleaf.common.config.MinioProperties;
import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * MinIO-backed implementation of {@link StorageService}.
 *
 * MinIO is S3-compatible object storage — it stores arbitrary files (PDFs,
 * PNGs, etc.) as objects identified by a bucket + key pair, similar to
 * files on a filesystem. Unlike a database, objects are never modified in place —
 * we overwrite by writing to the same key, which is exactly what we want for
 * idempotent tile regeneration.
 *
 * @Primary is not needed since this is the only @Service implementation in
 * main sources (the test implementation lives in @TestConfiguration).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MinioStorageService implements StorageService {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    @Override
    public void put(String bucket, String key, byte[] content, String contentType) {
        try {
            // Ensure the bucket exists — minio-init sidecar creates them at startup,
            // but this guard prevents NPE in tests.
            ensureBucketExists(bucket);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(new ByteArrayInputStream(content), content.length, -1)
                            .contentType(contentType)
                            .build()
            );
            log.debug("Stored object: bucket={}, key={}, size={} bytes", bucket, key, content.length);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload object to MinIO: bucket=%s key=%s".formatted(bucket, key), e);
        }
    }

    @Override
    public byte[] get(String bucket, String key) {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read object from MinIO: bucket=%s key=%s".formatted(bucket, key), e);
        }
    }

    @Override
    public String presignedGetUrl(String bucket, String key, Duration ttl) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(key)
                            .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL: bucket=%s key=%s".formatted(bucket, key), e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(key).build()
            );
        } catch (Exception e) {
            log.warn("Failed to delete object from MinIO (may not exist): bucket={}, key={}: {}", bucket, key, e.getMessage());
        }
    }

    private void ensureBucketExists(String bucket) throws Exception {
        boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!found) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("Created MinIO bucket: {}", bucket);
        }
    }
}

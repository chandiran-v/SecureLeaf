package com.secureleaf.viewer.cache;

import com.github.benmanes.caffeine.cache.Ticker;
import com.secureleaf.viewer.metrics.ViewerMetrics;
import com.secureleaf.viewer.repository.ViewerSessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 13 acceptance criterion 5 — plain JUnit, no Spring, no Docker. */
class DiskTileCacheTest {

    @TempDir
    Path dir;

    private final AtomicLong nowNanos = new AtomicLong();
    private final Ticker fakeTicker = nowNanos::get;
    private ViewerMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new ViewerMetrics(new SimpleMeterRegistry(), Mockito.mock(ViewerSessionRepository.class), "java2d");
    }

    private DiskTileCache cache(long maxBytes) {
        return new DiskTileCache(dir, Duration.ofMinutes(15), maxBytes, fakeTicker, metrics);
    }

    private static TileCacheKey key(long user, int page) {
        return new TileCacheKey(user, 1, 1, page, TileCacheKey.DEFAULT_VARIANT, "java2d", "1");
    }

    private long fileCount() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @Test
    void putThenGet_returnsSameBytes() {
        DiskTileCache cache = cache(1_000_000);
        cache.put(key(1, 1), new byte[]{1, 2, 3});
        assertThat(cache.get(key(1, 1))).hasValue(new byte[]{1, 2, 3});
        assertThat(cache.get(key(1, 2))).isEmpty();
    }

    @Test
    void entryExpiresAfterTtl_andItsFileIsDeleted() throws IOException {
        DiskTileCache cache = cache(1_000_000);
        cache.put(key(1, 1), new byte[]{1, 2, 3});

        nowNanos.addAndGet(Duration.ofMinutes(14).toNanos());
        assertThat(cache.get(key(1, 1))).isPresent();

        nowNanos.addAndGet(Duration.ofMinutes(2).toNanos());
        assertThat(cache.get(key(1, 1))).isEmpty();
        cache.cleanUp();
        assertThat(fileCount()).isZero();
    }

    /**
     * Caffeine picks victims by W-TinyLFU (recency + frequency), not strict FIFO, so which entry
     * goes is its call; what the size bound guarantees, and what this pins, is that the index AND
     * the disk stay within the bound and every evicted entry's file is really deleted.
     */
    @Test
    void sizeBound_evictsEntries_andDeletesTheirFiles() throws IOException {
        DiskTileCache cache = cache(1_000);
        for (int page = 1; page <= 20; page++) {
            cache.put(key(1, page), new byte[400]);
            nowNanos.addAndGet(1_000);
        }
        cache.cleanUp();

        assertThat(cache.estimatedEntries()).isLessThanOrEqualTo(2);
        assertThat(fileCount()).as("files on disk == entries in the index").isEqualTo(cache.estimatedEntries());
        long readable = java.util.stream.IntStream.rangeClosed(1, 20)
                .filter(page -> cache.get(key(1, page)).isPresent()).count();
        assertThat(readable).isEqualTo(cache.estimatedEntries());
        assertThat(cache.get(key(1, 1))).as("the very first entry was evicted").isEmpty();
    }

    @Test
    void concurrentPutsOfTheSameKey_neverCorruptTheEntry() throws Exception {
        DiskTileCache cache = cache(100_000_000);
        TileCacheKey key = key(1, 1);
        byte[] a = new byte[200_000];
        byte[] b = new byte[200_000];
        java.util.Arrays.fill(a, (byte) 'a');
        java.util.Arrays.fill(b, (byte) 'b');

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            byte[] payload = i % 2 == 0 ? a : b;
            futures.add(pool.submit(() -> {
                go.await();
                for (int n = 0; n < 20; n++) {
                    cache.put(key, payload);
                    byte[] read = cache.get(key).orElseThrow();
                    assertThat(read).isIn((Object) a, (Object) b);   // whole payload, never a mix
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(cache.get(key)).hasValueSatisfying(v -> assertThat(v).isIn((Object) a, (Object) b));
        assertThat(fileCount()).as("losers were deleted, only one file remains").isEqualTo(1);
    }

    @Test
    void restartWithStaleDirectory_isSafe_staleFilesAreWiped() throws IOException {
        Files.write(dir.resolve("deadbeef-1.png"), new byte[]{9});
        Files.write(dir.resolve("deadbeef-2.part"), new byte[]{9});
        Files.write(dir.resolve("unrelated.txt"), new byte[]{9});

        DiskTileCache cache = cache(1_000_000);

        assertThat(dir.resolve("deadbeef-1.png")).doesNotExist();
        assertThat(dir.resolve("deadbeef-2.part")).doesNotExist();
        assertThat(dir.resolve("unrelated.txt")).as("only our own files are touched").exists();
        cache.put(key(1, 1), new byte[]{1});
        assertThat(cache.get(key(1, 1))).isPresent();
    }

    @Test
    void danglingIndexEntry_whoseFileVanished_isAMiss() throws IOException {
        DiskTileCache cache = cache(1_000_000);
        cache.put(key(1, 1), new byte[]{1});
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Files.delete(f);
            }
        }
        assertThat(cache.get(key(1, 1))).isEmpty();
    }

    @Test
    void evictUser_removesOnlyThatUsersEntries() throws IOException {
        DiskTileCache cache = cache(1_000_000);
        cache.put(key(1, 1), new byte[]{1});
        cache.put(key(1, 2), new byte[]{2});
        cache.put(key(2, 1), new byte[]{3});

        cache.evictUser(1);

        assertThat(cache.get(key(1, 1))).isEmpty();
        assertThat(cache.get(key(1, 2))).isEmpty();
        assertThat(cache.get(key(2, 1))).isPresent();
        assertThat(fileCount()).isEqualTo(1);
    }

    @Test
    void everyKeyFieldChangesTheHash() {
        TileCacheKey base = key(1, 1);
        assertThat(base.hash()).hasSize(64);
        assertThat(new TileCacheKey(2, 1, 1, 1, "default", "java2d", "1").hash()).isNotEqualTo(base.hash());
        assertThat(new TileCacheKey(1, 2, 1, 1, "default", "java2d", "1").hash()).isNotEqualTo(base.hash());
        assertThat(new TileCacheKey(1, 1, 2, 1, "default", "java2d", "1").hash()).isNotEqualTo(base.hash());
        assertThat(new TileCacheKey(1, 1, 1, 2, "default", "java2d", "1").hash()).isNotEqualTo(base.hash());
        assertThat(new TileCacheKey(1, 1, 1, 1, "thumb", "java2d", "1").hash()).isNotEqualTo(base.hash());
        assertThat(new TileCacheKey(1, 1, 1, 1, "default", "libvips", "1").hash()).isNotEqualTo(base.hash());
        assertThat(new TileCacheKey(1, 1, 1, 1, "default", "java2d", "2").hash()).isNotEqualTo(base.hash());
    }
}

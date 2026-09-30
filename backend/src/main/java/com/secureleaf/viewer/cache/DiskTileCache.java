package com.secureleaf.viewer.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.secureleaf.viewer.metrics.ViewerMetrics;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 13, D3 — the default cache for a single server: tile bytes live in files, and a small
 * in-heap Caffeine map is the INDEX ({@code key -> file path, size, userId}) that owns the TTL and
 * the total-size bound. Heap cost is a few hundred bytes per entry instead of ~500 KB.
 *
 * Design points, each of which is a bug if removed:
 * <ul>
 *   <li><b>Atomic writes</b> — bytes go to a {@code .part} temp file, then {@code ATOMIC_MOVE}s to
 *       the final name, so a reader never sees half a PNG and a crash never leaves one behind
 *       under a real name.</li>
 *   <li><b>One file per entry</b> (unique name per put) — two concurrent puts of the same key
 *       write different files; whichever is indexed last wins and the other is deleted by the
 *       removal listener. Nothing is ever overwritten in place.</li>
 *   <li><b>Delete on eviction</b> — the removal listener deletes the file for every cause
 *       (TTL, size, explicit, replaced), so the size bound bounds disk, not just the index.</li>
 *   <li><b>Startup wipe</b> — the index is in memory, so files from a previous run are
 *       unreachable orphans (and could belong to a revoked buyer); they are deleted at start.</li>
 *   <li>Caffeine runs maintenance and listeners on the calling thread ({@code executor(Runnable::run)})
 *       so eviction is deterministic — and testable with a fake {@link Ticker}.</li>
 * </ul>
 */
@Slf4j
public class DiskTileCache implements WatermarkedTileCache {

    private static final String SUFFIX = ".png";
    private static final String PART_SUFFIX = ".part";

    private record Entry(Path file, int size, long userId) {
    }

    private final Path dir;
    private final ViewerMetrics metrics;
    private final Cache<String, Entry> index;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong();

    public DiskTileCache(Path dir, Duration ttl, long maxBytes, Ticker ticker, ViewerMetrics metrics) {
        this.dir = dir;
        this.metrics = metrics;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create tile cache directory " + dir, e);
        }
        wipeStaleFiles();
        this.index = Caffeine.newBuilder()
                .executor(Runnable::run)
                .ticker(ticker)
                .expireAfterWrite(ttl)
                .maximumWeight(maxBytes)
                .weigher((String k, Entry v) -> v.size())
                .removalListener((String k, Entry v, RemovalCause cause) -> onRemoved(v, cause))
                .build();
        metrics.registerTileCacheSize(totalBytes::get);
    }

    @Override
    public Optional<byte[]> get(TileCacheKey key) {
        String hash = key.hash();
        // A few attempts: a concurrent put of the same key can replace the entry (and delete the old
        // file) between our index lookup and our read; the next lookup then finds the new entry.
        for (int attempt = 0; attempt < 3; attempt++) {
            Entry entry = index.getIfPresent(hash);
            if (entry == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(Files.readAllBytes(entry.file()));
            } catch (NoSuchFileException e) {
                // Drop only THIS entry: invalidate(hash) could remove a fresh replacement.
                index.asMap().remove(hash, entry);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Optional.empty();
    }

    @Override
    public void put(TileCacheKey key, byte[] watermarkedBytes) {
        String hash = key.hash();
        String name = hash + "-" + sequence.incrementAndGet();
        Path temp = dir.resolve(name + PART_SUFFIX);
        Path target = dir.resolve(name + SUFFIX);
        try {
            Files.write(temp, watermarkedBytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException(e);
        }
        totalBytes.addAndGet(watermarkedBytes.length);
        index.put(hash, new Entry(target, watermarkedBytes.length, key.userId()));
        index.cleanUp();
    }

    @Override
    public void evictUser(long userId) {
        index.asMap().entrySet().removeIf(e -> e.getValue().userId() == userId);
    }

    /** Runs pending expiry now (used by tests with a fake ticker). */
    public void cleanUp() {
        index.cleanUp();
    }

    public long estimatedEntries() {
        return index.estimatedSize();
    }

    private void onRemoved(Entry entry, RemovalCause cause) {
        if (entry == null) {
            return;
        }
        deleteQuietly(entry.file());
        totalBytes.addAndGet(-entry.size());
        metrics.recordTileCacheEviction(cause.name().toLowerCase());
    }

    private void wipeStaleFiles() {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.endsWith(SUFFIX) || name.endsWith(PART_SUFFIX)) {
                    deleteQuietly(file);
                }
            }
        } catch (IOException e) {
            log.warn("Could not clean stale tile cache files in {}", dir, e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete tile cache file {}", file, e);
        }
    }
}

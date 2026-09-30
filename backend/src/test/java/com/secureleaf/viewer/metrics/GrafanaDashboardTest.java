package com.secureleaf.viewer.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 acceptance criterion 3 — the committed Grafana dashboard parses, and every
 * {@code secureleaf_*} series its queries mention is one {@link ViewerMetrics} really publishes
 * (so renaming a meter without updating the dashboard fails the build, instead of leaving an
 * empty panel discovered mid load-test). Plain JUnit: no Spring context, no Docker.
 */
class GrafanaDashboardTest {

    private static final Path DASHBOARD = Path.of("../infra/grafana/dashboards/secureleaf-viewer.json");
    private static final Pattern SERIES = Pattern.compile("secureleaf_[a-z_]+");

    @Test
    void dashboardParses_andQueriesReferenceOnlyRealMeters() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(DASHBOARD));
        assertThat(root.path("panels")).isNotEmpty();

        Set<String> used = new HashSet<>();
        for (JsonNode panel : root.path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                Matcher m = SERIES.matcher(target.path("expr").asText());
                while (m.find()) {
                    used.add(m.group());
                }
            }
        }

        assertThat(used).isNotEmpty();
        assertThat(used).isSubsetOf(publishedSeries());
    }

    /** Micrometer -> Prometheus naming: dots become underscores; timers add _seconds_*. */
    private static Set<String> publishedSeries() {
        Set<String> names = new HashSet<>();
        for (String timer : new String[]{
                ViewerMetrics.TILE_REQUEST, ViewerMetrics.WATERMARK_RENDER, ViewerMetrics.STORAGE_FETCH,
                ViewerMetrics.RENDER_WAIT}) {
            for (String suffix : new String[]{"count", "sum", "bucket", "max"}) {
                names.add(timer.replace('.', '_') + "_seconds_" + suffix);
            }
        }
        for (String suffix : new String[]{"count", "sum", "bucket", "max"}) {
            names.add(ViewerMetrics.TILE_BYTES.replace('.', '_') + "_" + suffix);
        }
        names.add(ViewerMetrics.SESSIONS_ACTIVE.replace('.', '_'));
        names.add(ViewerMetrics.RENDER_QUEUE_SIZE.replace('.', '_'));
        names.add(ViewerMetrics.RENDER_ACTIVE.replace('.', '_'));
        names.add(ViewerMetrics.RENDER_REJECTED.replace('.', '_') + "_total");
        names.add(ViewerMetrics.RENDER_TIMEOUT.replace('.', '_') + "_total");
        names.add(ViewerMetrics.TILECACHE_HIT.replace('.', '_') + "_total");
        names.add(ViewerMetrics.TILECACHE_MISS.replace('.', '_') + "_total");
        names.add(ViewerMetrics.TILECACHE_SIZE_BYTES.replace('.', '_'));
        names.add(ViewerMetrics.TILECACHE_EVICTIONS.replace('.', '_') + "_total");
        return names;
    }
}

package com.secureleaf.content;

import com.secureleaf.content.watermark.WatermarkSmokeCheck;
import org.junit.jupiter.api.Test;

/** Phase 09D D6 — the same check the Docker image runs; here it proves the renderer draws pixels. */
class WatermarkSmokeCheckTest {

    @Test
    void smokeCheck_drawsPixels() throws Exception {
        WatermarkSmokeCheck.main(new String[0]); // System.exit(1) would fail the build
    }
}

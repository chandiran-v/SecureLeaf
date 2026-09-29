#!/bin/sh
# Phase 09D D6 — proves the watermark renderer works INSIDE a built backend image (fonts present).
# Usage: infra/prod/scripts/smoke-watermark.sh [image]     (default secureleaf/backend:local)
# Exits non-zero, printing why, if no pixels changed or the JVM cannot load fonts.
set -eu
IMAGE=${1:-secureleaf/backend:${IMAGE_TAG:-local}}
exec docker run --rm --entrypoint java "$IMAGE" \
    -Djava.awt.headless=true \
    -Dloader.main=com.secureleaf.content.watermark.WatermarkSmokeCheck \
    -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher

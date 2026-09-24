# Phase 14 — libvips watermark renderer

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **MVP2-01**.
> Depends on: Phases 10–13. Target branch: `feature/secure-leaf-mvp2`.
>
> ## ⚠️ Owner decision required before this phase runs
> Its Issue is created with `phase:blocked`. Confirm **D1** (or pick another option) on the Issue,
> update the workflow files this needs (see D1, "Owner-side changes"), then remove the label.

## Context

`WatermarkRenderer` has been a Strategy interface since Phase 3, so that this swap would be "a new implementation plus a config flag". Java2D decodes the whole PNG into a heap `BufferedImage`, draws, and re-encodes. libvips streams pixels in small regions, using a fraction of the RAM, and is usually several times faster. Phase 10's `secureleaf.watermark.render` timer tells us whether this matters, and the Phase 13 cache reduces how often it runs.

## Decisions

- **D1 — How Java calls libvips.** *(owner to confirm)*

  | Option | How | Cost |
  |---|---|---|
  | **A (recommended)** | Upgrade the backend to **Java 25 LTS** and use **`vips-ffm`**, which calls libvips via the Foreign Function & Memory API (final since Java 22). | JDK upgrade (pom, Dockerfile, CI workflows). No JNI glue to compile. The FFM API is the modern standard. |
  | B | Stay on Java 21 and use a JNI binding (e.g. criteo JVips) | Native build per OS/arch, a stale upstream, and segfault-level debugging. This is exactly why `requirements.md` deferred it. |
  | C | Call a sidecar (a small Go or Rust service wrapping libvips) over HTTP on localhost | Extra deployable and a network hop per tile. Keeps the JVM pure. |

  **Owner-side changes for A:** set `java-version: '25'` in `.github/workflows/backend-ci.yml` and `phase-scheduler.yml`, and update the `CLAUDE.md` tech stack line. Automated runs can't edit `.github/`.
- **D2 — Implementation (assuming A).** `LibvipsWatermarkRenderer implements WatermarkRenderer`, selected by `drm.watermark.renderer: libvips` through `@ConditionalOnProperty`. `java2d` stays the default until the benchmark (D5) proves libvips is better.
  - Render text with `vips_text`, rotate it, and composite with `vips_composite2` at the configured opacity.
  - The output must match Java2D's *semantics*: the same label, diagonal, and opacity range. It doesn't have to be pixel-identical.
- **D3 — Native dependency packaging.** Install libvips in the runtime image (`infra/docker/backend.Dockerfile`: `apt-get install -y --no-install-recommends libvips42` on a Debian-based JRE image). Document the local-dev installation for Windows, macOS and Linux in the note.
  - At startup, if `libvips` is selected but the library can't load, **fail fast** with a clear message. Never fall back silently.
- **D4 — Memory safety.** All FFM arenas are scoped per call (`Arena.ofConfined()` in try-with-resources). The render pool from Phase 12 bounds concurrency. Add a soak test: 10,000 renders with no RSS growth beyond a threshold, recorded in the note.
- **D5 — Benchmark.** A JMH benchmark module (or a simple harness if JMH is too heavy) comparing Java2D and libvips on the bundled sample pages: ops/s, p99, and allocated bytes per op. Put the results in `docs/perf/renderer-comparison.md`. Flip the default to `libvips` **only if** the benchmark shows at least a 2× improvement. Otherwise keep `java2d` and say so. Either way is a valid, honest outcome.
- **D6 — Contract tests.** A shared abstract `WatermarkRendererContractTest` runs against both implementations. It checks:
  - the output is a valid PNG with the same dimensions as the input
  - pixels differ from the input in the watermark region
  - a label containing unicode (for example Tamil or Hindi names in emails) renders without error
  - empty or oversized labels are handled

  If libvips isn't present in the environment, skip the libvips run with an assumption message.

## Acceptance criteria
1. The contract test passes for Java2D everywhere, and for libvips wherever the library is available (the Docker image build runs it).
2. With the renderer set to `libvips` and the library missing, startup fails with a clear message.
3. The soak test shows no native memory growth trend.
4. Benchmark results are committed, and the default renderer choice is justified by them.
5. The Docker image builds and renders a tile with libvips (smoke script).

## Out of scope
- GPU rendering.
- Moving the watermark to the edge or CDN.

## Learning note
Create `docs/learning-notes/phase-14-libvips-renderer.md`. Headline topics:
- the Strategy pattern paying off (the swap touched no callers)
- JNI vs FFM vs sidecar
- native memory and arenas
- streaming image pipelines vs whole-image buffers
- benchmarking pitfalls (JIT warm-up, dead-code elimination, why JMH exists)
- "prove it before you switch"

package com.secureleaf.content.tiles;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * {@code content.tiles.*} (Phase 16, D1) — the resolutions every page is rendered at. A list, so a
 * {@code TABLET} variant is one more entry in {@code application.yml}: the schema stores the name
 * as text and nothing in the code switches on it.
 *
 * @param variants at least one, and one named {@link #DESKTOP}: the fallback for every other variant
 */
@ConfigurationProperties(prefix = "content.tiles")
public record TileVariantProperties(List<Variant> variants) {

    public static final String DESKTOP = "DESKTOP";

    /**
     * @param name    stored in {@code content_pages.variant}, signed into the tile URL, sent by the client
     * @param widthPx how wide the viewer should consider this variant (D5). For a width-driven variant
     *                (no {@code dpi}) it is also the exact width rendered; for a DPI-driven one (DESKTOP)
     *                it is the nominal width of a typical page, e.g. A4 at 150 DPI = 1240
     * @param dpi     optional: render at this DPI, so the width follows the page's physical size.
     *                {@code DESKTOP} keeps the MVP1 150 DPI this way
     */
    public record Variant(String name, int widthPx, Integer dpi) {
        public Variant {
            if (name == null || name.isBlank() || name.length() > 16) {
                throw new IllegalArgumentException("A tile variant needs a name of 1-16 characters");
            }
            name = name.toUpperCase(Locale.ROOT);
            if (widthPx < 1) {
                throw new IllegalArgumentException("Tile variant " + name + " needs a positive width-px");
            }
            if (dpi != null && dpi < 1) {
                throw new IllegalArgumentException("Tile variant " + name + " has an invalid dpi");
            }
        }
    }

    public TileVariantProperties {
        if (variants == null || variants.isEmpty()) {
            variants = List.of(new Variant(DESKTOP, 1240, 150), new Variant("MOBILE", 900, null));
        }
        if (variants.stream().noneMatch(v -> v.name().equals(DESKTOP))) {
            throw new IllegalArgumentException("content.tiles.variants must include " + DESKTOP);
        }
        if (variants.stream().map(Variant::name).distinct().count() != variants.size()) {
            throw new IllegalArgumentException("content.tiles.variants has a duplicate name");
        }
        variants = List.copyOf(variants);
    }

    public Variant desktop() {
        return variants.stream().filter(v -> v.name().equals(DESKTOP)).findFirst().orElseThrow();
    }

    /** The configured variant of that name, or {@link #desktop()} for an unknown or null one. */
    public Variant resolve(String name) {
        if (name == null) return desktop();
        String wanted = name.toUpperCase(Locale.ROOT);
        return variants.stream().filter(v -> v.name().equals(wanted)).findFirst().orElseGet(this::desktop);
    }

    public boolean isKnown(String name) {
        return name != null && variants.stream().anyMatch(v -> v.name().equals(name));
    }
}

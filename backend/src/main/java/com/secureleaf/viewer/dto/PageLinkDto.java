package com.secureleaf.viewer.dto;

import com.secureleaf.content.entity.ContentPageLink;

/**
 * GraphQL {@code PageLink} — one clickable area on a page: fractions (0..1) of the page image,
 * origin top-left, plus either an external {@code url} or a {@code targetPage} in this document.
 */
public record PageLinkDto(double left, double top, double width, double height,
                          String type, String url, Integer targetPage) {

    public static PageLinkDto from(ContentPageLink link) {
        return new PageLinkDto(link.getLeftRatio(), link.getTopRatio(), link.getWidthRatio(), link.getHeightRatio(),
                link.getLinkType().name(), link.getTargetUrl(), link.getTargetPage());
    }
}

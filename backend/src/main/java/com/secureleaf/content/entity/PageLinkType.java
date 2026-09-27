package com.secureleaf.content.entity;

/** What a link in a PDF page points at. */
public enum PageLinkType {
    /** An external address: http, https or mailto only (anything else is dropped at extraction). */
    URL,
    /** Another page of the same document. */
    PAGE
}

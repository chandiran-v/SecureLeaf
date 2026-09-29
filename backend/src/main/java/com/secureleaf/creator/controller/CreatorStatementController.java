package com.secureleaf.creator.controller;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.creator.service.StatementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Phase 09C D4 — CSV download of a monthly statement (REST for file downloads, GraphQL for data).
 * "Owner-only" by construction: SecurityConfig limits {@code /api/creator/**} to CREATOR, and the
 * creator id comes from the JWT — there is no id in the URL to tamper with.
 */
@RestController
@RequestMapping("/api/creator/statements")
@RequiredArgsConstructor
public class CreatorStatementController {

    private final StatementService statementService;

    @GetMapping("/{month}.csv")
    public ResponseEntity<byte[]> csv(@PathVariable String month, @AuthenticationPrincipal SecureLeafUserDetails user) {
        String csv = statementService.statementCsv(user.getUserId(), month);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"secureleaf-statement-" + month + ".csv\"")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}

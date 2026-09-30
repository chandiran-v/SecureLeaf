package com.secureleaf.content.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.content.dto.DocumentVersionDto;
import com.secureleaf.content.service.DocumentVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import java.util.List;

/** GraphQL entry points for the creator's version history and version retirement (Phase 15). */
@Controller
@RequiredArgsConstructor
public class DocumentVersionResolver {

    private final DocumentVersionService documentVersionService;

    @QueryMapping
    @PreAuthorize("hasRole('CREATOR')")
    public List<DocumentVersionDto> productVersions(@Argument Long productId) {
        return documentVersionService.listVersions(productId, currentUserId());
    }

    @MutationMapping
    @PreAuthorize("hasRole('CREATOR')")
    public boolean retireDocumentVersion(@Argument Long productId, @Argument Long versionId) {
        documentVersionService.retireVersion(productId, versionId, currentUserId());
        return true;
    }

    private Long currentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}

package com.secureleaf.auth.resolver;

import com.secureleaf.auth.dto.AuthPayload;
import com.secureleaf.auth.dto.BecomeCreatorInput;
import com.secureleaf.auth.dto.LoginInput;
import com.secureleaf.auth.dto.RegisterInput;
import com.secureleaf.auth.dto.UserDto;
import com.secureleaf.auth.mapper.UserMapper;
import com.secureleaf.auth.service.AuthService;
import com.secureleaf.auth.service.SecureLeafUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

/**
 * GraphQL resolver for all authentication operations and the {@code User} type mappings.
 *
 * <p>Public mutations: {@code register}, {@code login}, {@code googleLogin}, {@code refreshToken}.<br/>
 * Protected: {@code me} (query), {@code logout} (mutation).</p>
 */
@Controller
@RequiredArgsConstructor
@Validated
public class AuthResolver {

    private final AuthService authService;

    // ── Mutations ───────────────────────────────────────────────────────────────

    @MutationMapping
    public UserDto register(@Argument @Valid RegisterInput input) {
        return UserMapper.toDto(authService.register(input.email(), input.password(), input.displayName()));
    }

    @MutationMapping
    public AuthPayload login(@Argument @Valid LoginInput input) {
        return authService.login(input.email(), input.password());
    }

    @MutationMapping
    public AuthPayload googleLogin(@Argument String idToken) {
        return authService.googleLogin(idToken);
    }

    @MutationMapping
    public AuthPayload refreshToken(@Argument String token) {
        return authService.refreshToken(token);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public boolean logout(@Argument String refreshToken) {
        authService.logout(refreshToken);
        return true;
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public UserDto becomeCreator(@Argument BecomeCreatorInput input) {
        SecureLeafUserDetails userDetails = getCurrentUser();
        return UserMapper.toDto(
                authService.becomeCreator(
                        userDetails.getUserId(),
                        input.bio(),
                        input.payoutEmail(),
                        input.payoutUpi()
                )
        );
    }

    // ── Queries ─────────────────────────────────────────────────────────────────

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public UserDto me() {
        SecureLeafUserDetails userDetails = getCurrentUser();
        return UserMapper.toDto(authService.getUserById(userDetails.getUserId()));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private SecureLeafUserDetails getCurrentUser() {
        return (SecureLeafUserDetails) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
    }
}

package com.secureleaf.auth.service;

import com.secureleaf.auth.dto.AuthPayload;
import com.secureleaf.auth.entity.*;
import com.secureleaf.auth.mapper.UserMapper;
import com.secureleaf.auth.repository.RefreshTokenRepository;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.repository.UserRoleRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.DuplicateResourceException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.creator.entity.CreatorProfile;
import com.secureleaf.creator.repository.CreatorProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Core authentication service â€” handles registration, login, token refresh,
 * logout, and Google OAuth2 login.
 *
 * <h3>Refresh Token Rotation</h3>
 * On every refresh, the old token is revoked and a new one issued.
 * If a revoked token is reused (indicating a stolen token), all sessions
 * for that user are immediately revoked as a security precaution.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final GoogleOAuthService googleOAuthService;

    private static String normalizeEmail(String email) {
        return email == null ? null : email.toLowerCase().trim();
    }

    // â”€â”€ Register â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Transactional
    public User register(String email, String password, String displayName) {
        String normalizedEmail = normalizeEmail(email);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("User", "email", normalizedEmail);
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName.trim());
        user.setAuthProvider(AuthProvider.LOCAL);
        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateResourceException("User", "email", normalizedEmail);
        }

        assignDefaultRole(user);
        log.info("New user registered: id={}, email={}", user.getId(), user.getEmail());
        return user;
    }

    // â”€â”€ Login â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Transactional
    public AuthPayload login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        User user = userRepository.findWithRolesByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password"));

        if (user.getAuthProvider() != AuthProvider.LOCAL) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                    "This account uses Google sign-in. Please sign in with Google.");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }

        validateAccountActive(user);

        log.info("User logged in: id={}, email={}", user.getId(), user.getEmail());
        return generateTokens(user).payload();
    }

    // â”€â”€ Google Login â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Transactional
    public AuthPayload googleLogin(String idToken) {
        GoogleOAuthService.GoogleUserInfo googleUser = googleOAuthService.verifyIdToken(idToken);

        User user = userRepository.findWithRolesByGoogleSub(googleUser.sub())
                .orElseGet(() -> createGoogleUser(googleUser));

        validateAccountActive(user);

        log.info("Google login: id={}, email={}", user.getId(), user.getEmail());
        return generateTokens(user).payload();
    }

    // â”€â”€ Refresh Token â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Transactional
    public AuthPayload refreshToken(String rawRefreshToken) {
        String tokenHash = jwtService.hashToken(rawRefreshToken);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN, "Invalid refresh token"));

        // Reuse detection
        if (storedToken.getRevokedAt() != null) {
            boolean isGracePeriod = storedToken.getReplacedBy() != null &&
                    storedToken.getRevokedAt().plusSeconds(30).isAfter(Instant.now());

            if (!isGracePeriod) {
                log.warn("Refresh token reuse detected outside grace period for user id={}", storedToken.getUser().getId());
                revokeAllUserTokens(storedToken.getUser());
                throw new BusinessException(ErrorCode.TOKEN_REUSE,
                        "Refresh token reuse detected. All sessions have been revoked for security.");
            }
        }

        // Check expiry
        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "Refresh token has expired. Please log in again.");
        }

        // Rotate
        storedToken.setRevokedAt(Instant.now());

        User user = userRepository.findWithRolesById(storedToken.getUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("User", storedToken.getUser().getId()));

        validateAccountActive(user);

        IssuedTokens tokens = generateTokens(user);
        storedToken.setReplacedBy(tokens.entity());

        return tokens.payload();
    }

    // â”€â”€ Logout â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Transactional
    public void logout(String rawRefreshToken) {
        String tokenHash = jwtService.hashToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHashForUpdate(tokenHash).ifPresent(token -> {
            token.setRevokedAt(Instant.now());
            log.info("User logged out (token revoked): id={}", token.getUser().getId());
        });
    }

    private record IssuedTokens(AuthPayload payload, RefreshToken entity) {}

    private IssuedTokens generateTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = jwtService.generateRefreshToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(jwtService.hashToken(rawRefreshToken));
        refreshToken.setExpiresAt(Instant.now().plusMillis(jwtService.getRefreshTokenExpiryMs()));
        refreshToken = refreshTokenRepository.save(refreshToken);

        return new IssuedTokens(new AuthPayload(accessToken, rawRefreshToken, UserMapper.toDto(user)), refreshToken);
    }

    private User createGoogleUser(GoogleOAuthService.GoogleUserInfo googleUser) {
        String normalizedEmail = normalizeEmail(googleUser.email());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL,
                    "An account with this email already exists. Please sign in with email and password.");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setDisplayName(googleUser.name());
        user.setAuthProvider(AuthProvider.GOOGLE);
        user.setGoogleSub(googleUser.sub());
        user.setAvatarUrl(googleUser.picture());
        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL,
                    "An account with this email already exists. Please sign in with email and password.");
        }

        assignDefaultRole(user);
        log.info("New Google user created: id={}, email={}", user.getId(), user.getEmail());
        return user;
    }

    private void assignDefaultRole(User user) {
        UserRole buyerRole = new UserRole();
        buyerRole.setUser(user);
        buyerRole.setRole(Role.BUYER);
        user.getRoles().add(buyerRole);
    }

    /**
     * Returns the user with roles eagerly loaded.
     * Uses findWithRolesById (EntityGraph) so UserMapper.toDto can safely iterate
     * user.getRoles() even with open-in-view=false.
     * Fix for bug A1: was using findById (lazy roles → LazyInitializationException).
     */
    @Transactional(readOnly = true)
    public User getUserById(Long id) {
        return userRepository.findWithRolesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    // ── Become Creator (AUTH-06) ─────────────────────────────────────────────

    /**
     * Idempotently grants the CREATOR role to a user and upserts their creator profile.
     *
     * Idempotent means: calling this twice has the same effect as calling it once.
     * We check whether the CREATOR UserRole row exists before inserting a new one,
     * and we either create or update the CreatorProfile.
     *
     * Important: a role change does NOT invalidate the already-issued 15-minute
     * access token. The client MUST call refreshToken immediately after this
     * mutation to obtain a JWT that carries ROLE_CREATOR.
     */
    @Transactional
    public User becomeCreator(Long userId, String bio, String payoutEmail, String payoutUpi) {
        User user = userRepository.findWithRolesById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // Idempotent: only add CREATOR role if the user doesn't already have it
        boolean alreadyCreator = user.getRoles().stream()
                .anyMatch(r -> r.getRole() == Role.CREATOR);
        if (!alreadyCreator) {
            UserRole creatorRole = new UserRole();
            creatorRole.setUser(user);
            creatorRole.setRole(Role.CREATOR);
            user.getRoles().add(creatorRole);
            log.info("CREATOR role granted to user id={}", userId);
        }

        // Upsert creator profile — create if absent, update fields if present
        CreatorProfile profile = creatorProfileRepository.findById(userId)
                .orElseGet(() -> {
                    CreatorProfile newProfile = new CreatorProfile();
                    // @MapsId: set the User reference, not the raw id
                    newProfile.setUser(user);
                    return newProfile;
                });
        if (bio != null) profile.setBio(bio);
        if (payoutEmail != null) profile.setPayoutEmail(payoutEmail);
        if (payoutUpi != null) profile.setPayoutUpi(payoutUpi);
        creatorProfileRepository.save(profile);

        log.info("Creator profile upserted for user id={}", userId);
        return user;
    }

    private void revokeAllUserTokens(User user) {
        refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
    }

    private void validateAccountActive(User user) {
        if (user.getAccountStatus() == AccountStatus.SUSPENDED) {
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED, "Your account has been suspended.");
        }
        if (user.getAccountStatus() == AccountStatus.DEACTIVATED || user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED, "This account has been deactivated.");
        }
    }
}

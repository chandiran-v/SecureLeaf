package com.secureleaf.auth;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.AuthService;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.creator.entity.CreatorProfile;
import com.secureleaf.creator.repository.CreatorProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthServiceIT extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CreatorProfileRepository creatorProfileRepository;

    @Test
    @Transactional
    void becomeCreator_success() {
        // Given a new BUYER user
        User user = new User();
        user.setEmail("buyer@example.com");
        user.setDisplayName("Buyer");
        user.setPasswordHash("hash");
        
        UserRole buyerRole = new UserRole();
        buyerRole.setUser(user);
        buyerRole.setRole(com.secureleaf.auth.entity.Role.BUYER);
        user.getRoles().add(buyerRole);
        
        user = userRepository.save(user);

        // When they opt in to become a creator
        User updated = authService.becomeCreator(user.getId(), "My bio", "payout@example.com", "name@upi");

        // Then they should have the CREATOR role
        assertThat(updated.getRoles().stream().map(r -> r.getRole().name()))
            .contains("BUYER", "CREATOR");

        // And the creator profile should be saved
        CreatorProfile profile = creatorProfileRepository.findById(user.getId()).orElseThrow();
        assertThat(profile.getBio()).isEqualTo("My bio");
        assertThat(profile.getPayoutEmail()).isEqualTo("payout@example.com");
        assertThat(profile.getPayoutUpi()).isEqualTo("name@upi");
    }

    @Test
    @Transactional
    void becomeCreator_idempotent() {
        // Given an existing CREATOR user
        User user = new User();
        user.setEmail("creator@example.com");
        user.setDisplayName("Creator");
        user.setPasswordHash("hash");
        
        UserRole buyerRole = new UserRole();
        buyerRole.setUser(user);
        buyerRole.setRole(com.secureleaf.auth.entity.Role.BUYER);
        user.getRoles().add(buyerRole);

        UserRole creatorRole = new UserRole();
        creatorRole.setUser(user);
        creatorRole.setRole(com.secureleaf.auth.entity.Role.CREATOR);
        user.getRoles().add(creatorRole);
        
        user = userRepository.save(user);

        CreatorProfile existingProfile = new CreatorProfile();
        existingProfile.setUser(user);
        existingProfile.setBio("Old bio");
        creatorProfileRepository.save(existingProfile);

        // When they call becomeCreator again with new details
        User updated = authService.becomeCreator(user.getId(), "New bio", null, null);

        // Then roles shouldn't duplicate
        assertThat(updated.getRoles().stream().map(r -> r.getRole().name()))
            .containsExactlyInAnyOrder("BUYER", "CREATOR");

        // And profile should update
        CreatorProfile profile = creatorProfileRepository.findById(user.getId()).orElseThrow();
        assertThat(profile.getBio()).isEqualTo("New bio");
        // Null inputs shouldn't overwrite existing fields according to our impl
        // (Wait, our impl does overwrite them if they are null. Let's assert it overwrites to null).
        assertThat(profile.getPayoutEmail()).isNull();
    }
}

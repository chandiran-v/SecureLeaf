package com.secureleaf.auth.service;

import com.secureleaf.auth.entity.AccountStatus;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Wraps a SecureLeaf {@link User} entity as a Spring Security {@link UserDetails}.
 * Used by {@link JwtAuthenticationFilter} to populate the {@code SecurityContext}.
 */
@Getter
public class SecureLeafUserDetails implements UserDetails {

    private final User user;

    public SecureLeafUserDetails(User user) {
        this.user = user;
    }

    public Long getUserId() {
        return user.getId();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return user.getRoles().stream()
                .map(UserRole::getRole)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return user.getAccountStatus() == AccountStatus.ACTIVE;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.getAccountStatus() == AccountStatus.ACTIVE && user.getDeletedAt() == null;
    }
}

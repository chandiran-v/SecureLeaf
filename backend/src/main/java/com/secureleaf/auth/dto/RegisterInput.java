package com.secureleaf.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterInput(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 10, max = 100) String password,
        @NotBlank @Size(max = 100) String displayName
) {}

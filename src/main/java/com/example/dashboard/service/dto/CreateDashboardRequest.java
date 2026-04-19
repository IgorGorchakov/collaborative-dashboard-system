package com.example.dashboard.service.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create-dashboard request.
 *
 * {@code username} is optional and forwarded back to the UI only — the server
 * does not persist it. Uniqueness is enforced at STOMP CONNECT time
 * (see UserHandshakeInterceptor) among currently-connected users of a dashboard.
 */
public record CreateDashboardRequest(
        @Min(100) @Max(8192) int width,
        @Min(100) @Max(8192) int height,
        @Size(min = 1, max = 32)
        @Pattern(regexp = "^[A-Za-z0-9 _.\\-]+$")
        String username
) {
}

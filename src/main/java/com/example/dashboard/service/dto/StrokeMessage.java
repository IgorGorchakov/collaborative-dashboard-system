package com.example.dashboard.service.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Payload sent by clients over STOMP to {@code /app/draw/{dashboardId}}.
 *
 * The client batches points in ~50ms windows (see spec Challenge 1) and sends
 * a single path segment instead of per-pixel events.
 *
 * The system is anonymous: {@code userId} is a client-supplied display name
 * (generated at first visit, stored in localStorage) — the server uses it for
 * echo suppression and per-user error routing, but does not verify it.
 */
public record StrokeMessage(
        @NotNull UUID dashboardId,
        @NotNull @Size(min = 1, max = 64) String userId,
        @NotEmpty @Size(max = 512) List<Point> points,
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String color,
        @Min(1) @Max(32) Integer thickness
) {
    public record Point(double x, double y) {}
}

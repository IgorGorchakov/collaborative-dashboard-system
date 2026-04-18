package com.example.dashboard.dto;

import com.example.dashboard.model.Dashboard;

import java.time.Instant;
import java.util.UUID;

public record DashboardResponse(UUID id, int width, int height, Instant createdAt) {
    public static DashboardResponse from(Dashboard d) {
        return new DashboardResponse(d.getId(), d.getWidth(), d.getHeight(), d.getCreatedAt());
    }
}

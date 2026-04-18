package com.example.dashboard.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CreateDashboardRequest(
        @Min(100) @Max(8192) int width,
        @Min(100) @Max(8192) int height
) {
}

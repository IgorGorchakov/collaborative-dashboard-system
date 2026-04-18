package com.example.dashboard.api.rest;

import com.example.dashboard.dto.CreateDashboardRequest;
import com.example.dashboard.dto.DashboardResponse;
import com.example.dashboard.service.DashboardService;
import com.example.dashboard.service.StrokeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/dashboards")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final StrokeService strokeService;

    @PostMapping
    public ResponseEntity<DashboardResponse> create(@Valid @RequestBody CreateDashboardRequest request) {
        return ResponseEntity.ok(DashboardResponse.from(dashboardService.create(request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DashboardResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(DashboardResponse.from(dashboardService.get(id)));
    }

    /**
     * Returns every persisted stroke for the dashboard, ordered by ordinal.
     * Each element is the raw JSON payload that was broadcast originally
     * (augmented with its {@code ordinal}), so the client can reuse the same
     * render path for history and live frames.
     *
     * Pagination + incremental replay is tracked in feature 18.
     */
    @GetMapping(value = "/{id}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> history(@PathVariable UUID id) {
        List<String> payloads = strokeService.history(id);
        StringBuilder sb = new StringBuilder(payloads.size() * 128 + 2);
        sb.append('[');
        for (int i = 0; i < payloads.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(payloads.get(i));
        }
        sb.append(']');
        return ResponseEntity.ok(sb.toString());
    }

    @PostMapping("/{id}/clear")
    public ResponseEntity<Void> clear(@PathVariable UUID id) {
        dashboardService.clear(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        dashboardService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

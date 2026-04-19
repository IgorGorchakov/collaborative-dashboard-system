package com.example.dashboard.api.rest;

import com.example.dashboard.service.dto.CreateDashboardRequest;
import com.example.dashboard.service.dto.DashboardResponse;
import com.example.dashboard.service.DashboardService;
import com.example.dashboard.service.StrokeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
     * Streams every persisted stroke for the dashboard, ordered by ordinal, as
     * a JSON array. Each element is the raw JSON payload that was broadcast
     * originally (augmented with its {@code ordinal}), so the client can reuse
     * the same render path for history and live frames.
     *
     * Payloads are streamed to the response as they are read from the DB
     * cursor (via {@code StreamingResponseBody} + a JPA {@code Stream}), so
     * the server never materializes the full response on the heap even for
     * very active dashboards.
     *
     * Pagination + incremental replay is tracked in feature 18.
     */
    @GetMapping(value = "/{id}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> history(@PathVariable UUID id) {
        dashboardService.get(id);
        StreamingResponseBody body = out -> strokeService.writeHistory(id, out);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @PostMapping("/{id}/clear")
    public ResponseEntity<Void> clear(@PathVariable UUID id) {
        dashboardService.clear(id);
        return ResponseEntity.noContent().build();
    }
}

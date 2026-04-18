package com.example.dashboard.service;

import com.example.dashboard.dto.CreateDashboardRequest;
import com.example.dashboard.model.Dashboard;
import com.example.dashboard.repository.DashboardRepository;
import com.example.dashboard.repository.StrokeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardRepository repository;
    private final StrokeRepository strokeRepository;
    private final ApplicationEventPublisher events;

    @Transactional
    public Dashboard create(CreateDashboardRequest request) {
        Dashboard dashboard = Dashboard.builder()
                .id(UUID.randomUUID())
                .width(request.width())
                .height(request.height())
                .createdAt(Instant.now())
                .build();
        return repository.save(dashboard);
    }

    public Dashboard get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dashboard not found: " + id));
    }

    /** Wipe all strokes for a dashboard (keeps the dashboard itself). */
    @Transactional
    public void clear(UUID id) {
        Dashboard d = get(id);
        strokeRepository.deleteByDashboardId(d.getId());
        strokeRepository.resetOrdinal(d.getId());
    }

    /** Delete dashboard + cascade strokes (FK ON DELETE CASCADE). */
    @Transactional
    public void delete(UUID id) {
        Dashboard d = get(id);
        repository.delete(d);
        events.publishEvent(new DashboardDeletedEvent(d.getId()));
    }

    /** Fired after a dashboard is deleted so caches can evict their entry. */
    public record DashboardDeletedEvent(UUID dashboardId) {}
}

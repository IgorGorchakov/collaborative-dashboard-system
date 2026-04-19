package com.example.dashboard.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-dashboard presence registry.
 *
 * A username is unique only among currently-connected users of a given dashboard.
 * Names are released when the STOMP session ends.
 */
@Component
public class UserService {

    /** dashboardId -> (sessionId -> username) */
    private final Map<UUID, Map<String, String>> byDashboard = new ConcurrentHashMap<>();

    /** sessionId -> dashboardId, for O(1) lookup from SessionDisconnectEvent. */
    private final Map<String, UUID> sessionIndex = new ConcurrentHashMap<>();

    /**
     * Attempt to claim {@code username} on {@code dashboardId} for this session.
     * Returns {@code false} if the name is already in use on that dashboard.
     */
    public boolean tryJoin(UUID dashboardId, String sessionId, String username) {
        Map<String, String> bucket = byDashboard.computeIfAbsent(dashboardId, id -> new ConcurrentHashMap<>());
        synchronized (bucket) {
            if (bucket.containsValue(username)) {
                return false;
            }
            bucket.put(sessionId, username);
        }
        sessionIndex.put(sessionId, dashboardId);
        return true;
    }

    /**
     * Remove the session from the registry. Returns the freed entry if the session was present.
     */
    public Optional<Leave> leave(String sessionId) {
        UUID dashboardId = sessionIndex.remove(sessionId);
        if (dashboardId == null) {
            return Optional.empty();
        }
        Map<String, String> bucket = byDashboard.get(dashboardId);
        if (bucket == null) {
            return Optional.empty();
        }
        String username;
        synchronized (bucket) {
            username = bucket.remove(sessionId);
            if (bucket.isEmpty()) {
                byDashboard.remove(dashboardId, bucket);
            }
        }
        if (username == null) {
            return Optional.empty();
        }
        return Optional.of(new Leave(dashboardId, username));
    }

    /** Total active sessions across all dashboards (for metrics / observability). */
    public int activeUserCount() {
        return sessionIndex.size();
    }

    /** Number of dashboards that currently have at least one connected user. */
    public int activeDashboardCount() {
        return byDashboard.size();
    }

    /** Sorted snapshot of active usernames for the given dashboard. */
    public List<String> usersOf(UUID dashboardId) {
        Map<String, String> bucket = byDashboard.get(dashboardId);
        if (bucket == null) {
            return List.of();
        }
        return bucket.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public record Leave(UUID dashboardId, String username) {}
}

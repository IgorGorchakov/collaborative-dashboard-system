package com.example.dashboard.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * In-memory, per-dashboard presence registry using Caffeine cache.
 * <p>
 * Uses a single Cache<String, SessionEntry> where key is sessionId.
 * SessionEntry contains both dashboardId and username for O(1) lookups.
 * <p>
 * A username is unique only among currently-connected users of a given dashboard.
 * Names are released when the STOMP session ends.
 * <p>
 * Memory leak prevention: Cache uses size-based eviction (maxSize) and
 * time-based expiration (expireAfterWrite) to automatically clean up stale entries.
 */
@Component
public class UserService {

    /**
     * Single cache for all sessions. Key is sessionId, value contains dashboardId and username.
     * Configured with:
     * - maxSize: limits total number of cached entries across all dashboards
     * - expireAfterWrite: automatically evicts entries after specified duration
     */
    private final Cache<String, SessionEntry> sessionCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .recordStats()
            .build();

    /**
     * Attempt to claim {@code username} on {@code dashboardId} for this session.
     * Returns {@code false} if the name is already in use on that dashboard.
     */
    public boolean tryJoin(UUID dashboardId, String sessionId, String username) {
        SessionEntry existing = sessionCache.getIfPresent(sessionId);
        if (existing != null && existing.dashboardId().equals(dashboardId)) {
            // Session already exists with same dashboard - update via put to refresh expiration
            sessionCache.put(sessionId, new SessionEntry(dashboardId, username));
            return true;
        }

        synchronized (dashboardId.toString().intern()) {
            List<String> users = usersOf(dashboardId);
            if (users.contains(username)) {
                return false;
            }
            sessionCache.put(sessionId, new SessionEntry(dashboardId, username));
            return true;
        }
    }

    /**
     * Remove the session from the registry. Returns the freed entry if the session was present.
     */
    public Optional<Leave> leave(String sessionId) {
        SessionEntry removed = sessionCache.asMap().remove(sessionId);
        if (removed == null) {
            return Optional.empty();
        }
        return Optional.of(new Leave(removed.dashboardId(), removed.username()));
    }

    /** Total active sessions across all dashboards (for metrics / observability). */
    public long activeUserCount() {
        return sessionCache.estimatedSize();
    }

    /** Number of dashboards that currently have at least one connected user. */
    public long activeDashboardCount() {
        return sessionCache.asMap().values().stream()
                .map(SessionEntry::dashboardId)
                .distinct()
                .count();
    }

    /** Sorted snapshot of active usernames for the given dashboard. */
    public List<String> usersOf(UUID dashboardId) {
        return sessionCache.asMap().values().stream()
                .filter(entry -> entry.dashboardId().equals(dashboardId))
                .map(SessionEntry::username)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** Cache statistics for monitoring (useful for debugging memory issues). */
    public String cacheStats() {
        return sessionCache.stats().toString();
    }

    public record Leave(UUID dashboardId, String username) {}

    /**
     * Session entry stored in the cache.
     * Contains both dashboardId and username for efficient lookups without multiple maps.
     */
    public record SessionEntry(UUID dashboardId, String username) {}
}

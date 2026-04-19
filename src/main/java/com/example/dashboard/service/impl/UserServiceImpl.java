package com.example.dashboard.service.impl;

import com.example.dashboard.service.UserService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

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
@Service
public class UserServiceImpl implements UserService {

    private final Cache<String, SessionEntry> sessionCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .recordStats()
            .build();

    @Override
    public boolean tryJoin(UUID dashboardId, String sessionId, String username) {
        SessionEntry existing = sessionCache.getIfPresent(sessionId);
        if (existing != null && existing.dashboardId().equals(dashboardId)) {
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

    @Override
    public Optional<Leave> leave(String sessionId) {
        SessionEntry removed = sessionCache.asMap().remove(sessionId);
        if (removed == null) {
            return Optional.empty();
        }
        return Optional.of(new Leave(removed.dashboardId(), removed.username()));
    }

    @Override
    public long activeUserCount() {
        return sessionCache.estimatedSize();
    }

    @Override
    public long activeDashboardCount() {
        return sessionCache.asMap().values().stream()
                .map(SessionEntry::dashboardId)
                .distinct()
                .count();
    }

    @Override
    public List<String> usersOf(UUID dashboardId) {
        return sessionCache.asMap().values().stream()
                .filter(entry -> entry.dashboardId().equals(dashboardId))
                .map(SessionEntry::username)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public record SessionEntry(UUID dashboardId, String username) {}
}

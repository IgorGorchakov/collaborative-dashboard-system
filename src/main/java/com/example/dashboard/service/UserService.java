package com.example.dashboard.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public contract for per-dashboard user presence tracking. */
public interface UserService {

    boolean tryJoin(UUID dashboardId, String sessionId, String username);

    Optional<Leave> leave(String sessionId);

    long activeUserCount();

    long activeDashboardCount();

    List<String> usersOf(UUID dashboardId);

    record Leave(UUID dashboardId, String username) {}
}

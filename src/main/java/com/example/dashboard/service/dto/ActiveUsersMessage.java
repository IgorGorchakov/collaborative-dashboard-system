package com.example.dashboard.service.dto;

import java.util.List;
import java.util.UUID;

/**
 * Broadcast payload for /topic/dashboard/{id}/users. Contains the full sorted
 * list of currently-connected usernames on the given dashboard.
 */
public record ActiveUsersMessage(UUID dashboardId, List<String> users) {
}

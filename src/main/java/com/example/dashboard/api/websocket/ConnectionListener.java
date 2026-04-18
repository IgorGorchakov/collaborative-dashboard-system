package com.example.dashboard.api.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Tracks WebSocket session lifecycle to maintain "active users" state and
 * clean up registries on dirty disconnects (spec Challenge 4).
 *
 * TODO: wire to a Redis-backed presence registry keyed by dashboardId.
 */
@Component
@Slf4j
public class ConnectionListener {

    @EventListener
    public void onConnect(SessionConnectedEvent event) {
        log.debug("WebSocket connected: {}", event.getMessage().getHeaders());
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        log.debug("WebSocket disconnected: sessionId={}", event.getSessionId());
    }
}

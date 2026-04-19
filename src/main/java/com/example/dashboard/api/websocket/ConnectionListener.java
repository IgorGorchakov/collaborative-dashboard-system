package com.example.dashboard.api.websocket;

import com.example.dashboard.service.dto.ActiveUsersMessage;
import com.example.dashboard.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks WebSocket session lifecycle to maintain active-users state.
 *
 * The username and dashboard id are placed on session attributes by
 * {@link UserHandshakeInterceptor} during the CONNECT frame. This listener
 * fans the resulting user list out to subscribers of
 * {@code /topic/dashboard/{id}/users} on join and on disconnect.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConnectionListener {

    private static final Pattern USERS_TOPIC = Pattern.compile("^/topic/dashboard/([0-9a-fA-F-]{36})/users$");

    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void onConnect(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null) {
            return;
        }
        UUID dashboardId = (UUID) attrs.get(UserHandshakeInterceptor.ATTR_DASHBOARD_ID);
        String username = (String) attrs.get(UserHandshakeInterceptor.ATTR_USERNAME);
        if (dashboardId == null || username == null) {
            return;
        }
        log.debug("Session connected: user={} dashboard={}", username, dashboardId);
        broadcastUsers(dashboardId);
    }

    /**
     * Re-broadcast the user list whenever someone subscribes to the users topic.
     *
     * Avoids a race: the connect-time broadcast in {@link #onConnect} may arrive
     * before the client has finished subscribing, leaving the panel empty for the
     * joining user. Sending again on SUBSCRIBE is cheap and guarantees delivery.
     */
    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        Matcher matcher = USERS_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            return;
        }
        UUID dashboardId;
        try {
            dashboardId = UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException ex) {
            return;
        }
        broadcastUsers(dashboardId);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        userService.leave(event.getSessionId()).ifPresent(leave -> {
            log.debug("Session disconnected: user={} dashboard={}", leave.username(), leave.dashboardId());
            broadcastUsers(leave.dashboardId());
        });
    }

    private void broadcastUsers(UUID dashboardId) {
        messagingTemplate.convertAndSend(
                "/topic/dashboard/" + dashboardId + "/users",
                new ActiveUsersMessage(dashboardId, userService.usersOf(dashboardId)));
    }
}

package com.example.dashboard.api.websocket;

import com.example.dashboard.presence.ActiveUserRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates and registers the identity carried on the STOMP CONNECT frame.
 *
 * Clients send two native headers:
 *   X-Dashboard-Id  — UUID of the dashboard the user is joining
 *   X-Username      — 1..32 chars, [A-Za-z0-9 _.-]
 *
 * On success, the dashboard id and username are stored as STOMP session
 * attributes so downstream handlers can trust them without re-parsing headers.
 * On failure (missing, malformed, or duplicate), a MessageDeliveryException is
 * thrown — the client sees a STOMP ERROR frame and the session is closed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserHandshakeInterceptor implements ChannelInterceptor {

    public static final String ATTR_DASHBOARD_ID = "dashboardId";
    public static final String ATTR_USERNAME = "username";

    public static final String HEADER_DASHBOARD_ID = "X-Dashboard-Id";
    public static final String HEADER_USERNAME = "X-Username";

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9 _.\\-]{1,32}$");

    private final ActiveUserRegistry registry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String rawDashboard = accessor.getFirstNativeHeader(HEADER_DASHBOARD_ID);
        String rawUsername = accessor.getFirstNativeHeader(HEADER_USERNAME);
        String sessionId = accessor.getSessionId();

        if (rawDashboard == null || rawUsername == null || sessionId == null) {
            throw reject(message, "missing_identity_headers");
        }

        UUID dashboardId;
        try {
            dashboardId = UUID.fromString(rawDashboard.trim());
        } catch (IllegalArgumentException ex) {
            throw reject(message, "invalid_dashboard_id");
        }

        String username = rawUsername.trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw reject(message, "invalid_username");
        }

        if (!registry.tryJoin(dashboardId, sessionId, username)) {
            throw reject(message, "username_taken");
        }

        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            attrs.put(ATTR_DASHBOARD_ID, dashboardId);
            attrs.put(ATTR_USERNAME, username);
        }

        log.debug("STOMP CONNECT accepted: session={} dashboard={} user={}", sessionId, dashboardId, username);
        return message;
    }

    private MessageDeliveryException reject(Message<?> message, String reason) {
        log.debug("STOMP CONNECT rejected: {}", reason);
        return new MessageDeliveryException(message, reason);
    }
}

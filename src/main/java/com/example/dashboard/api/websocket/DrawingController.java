package com.example.dashboard.api.websocket;

import com.example.dashboard.dto.StrokeMessage;
import com.example.dashboard.service.StrokeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class DrawingController {

    private final StrokeService strokeService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/draw/{dashboardId}")
    public void draw(@DestinationVariable UUID dashboardId,
                     @Valid @Payload StrokeMessage message,
                     @Header(name = SimpMessageHeaderAccessor.SESSION_ATTRIBUTES, required = false)
                     Map<String, Object> sessionAttributes) {
        if (!dashboardId.equals(message.dashboardId())) {
            return;
        }

        UUID sessionDashboard = sessionAttributes == null
                ? null : (UUID) sessionAttributes.get(UserHandshakeInterceptor.ATTR_DASHBOARD_ID);
        String sessionUsername = sessionAttributes == null
                ? null : (String) sessionAttributes.get(UserHandshakeInterceptor.ATTR_USERNAME);

        if (sessionDashboard == null || !sessionDashboard.equals(dashboardId)) {
            log.debug("Dropping stroke: session not bound to dashboard {}", dashboardId);
            return;
        }
        if (sessionUsername == null || !sessionUsername.equals(message.userId())) {
            log.debug("Dropping stroke: body userId={} does not match session username={}",
                    message.userId(), sessionUsername);
            return;
        }

        StrokeService.PersistedStroke persisted = strokeService.append(dashboardId, message);
        messagingTemplate.convertAndSend(
                "/topic/dashboard/" + dashboardId,
                persisted.payloadJson());
    }
}

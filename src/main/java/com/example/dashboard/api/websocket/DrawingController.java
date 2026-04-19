package com.example.dashboard.api.websocket;

import com.example.dashboard.service.dto.StrokeMessage;
import com.example.dashboard.service.StrokeService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.UUID;

import static com.example.dashboard.api.websocket.UserHandshakeInterceptor.ATTR_DASHBOARD_ID;
import static com.example.dashboard.api.websocket.UserHandshakeInterceptor.ATTR_USERNAME;

@Controller
@Slf4j
public class DrawingController {

    private final StrokeService strokeService;
    private final SimpMessagingTemplate messagingTemplate;

    private final Counter acceptedCounter;
    private final Counter droppedCounter;

    @Autowired
    public DrawingController(
            StrokeService strokeService,
            SimpMessagingTemplate messagingTemplate,
            MeterRegistry meterRegistry
    ) {
        this.strokeService = strokeService;
        this.messagingTemplate = messagingTemplate;
        this.acceptedCounter = Counter.builder("dashboard.stomp.messages")
                .description("STOMP /draw messages processed")
                .tag("outcome", "accepted")
                .register(meterRegistry);
        this.droppedCounter = Counter.builder("dashboard.stomp.messages")
                .description("STOMP /draw messages processed")
                .tag("outcome", "dropped")
                .register(meterRegistry);
    }

    @MessageMapping("/draw/{dashboardId}")
    public void draw(@DestinationVariable UUID dashboardId,
                     @Valid @Payload StrokeMessage message,
                     @Header(name = SimpMessageHeaderAccessor.SESSION_ATTRIBUTES, required = false)
                     Map<String, Object> sessionAttributes) {

        if (!validateStroke(dashboardId, message, sessionAttributes)) {
            droppedCounter.increment();
            return;
        }

        String payload = strokeService.save(dashboardId, message);
        messagingTemplate.convertAndSend("/topic/dashboard/" + dashboardId, payload);
        acceptedCounter.increment();
    }

    private boolean validateStroke(
            UUID dashboardId,
            StrokeMessage message,
            Map<String, Object> sessionAttributes
    ) {
        UUID sessionDashboard = sessionAttributes == null ? null : (UUID) sessionAttributes.get(ATTR_DASHBOARD_ID);
        String sessionUsername = sessionAttributes == null ? null : (String) sessionAttributes.get(ATTR_USERNAME);

        if (!dashboardId.equals(message.dashboardId())) {
            log.warn("Dropping stroke: dashboardId from request url {} does not equal dashboardId from message {}", dashboardId, message.dashboardId());
            return false;
        }

        if (sessionDashboard == null || !sessionDashboard.equals(dashboardId)) {
            log.warn("Dropping stroke: session not bound to dashboard {}", dashboardId);
            return false;
        }
        if (sessionUsername == null || !sessionUsername.equals(message.userId())) {
            log.warn("Dropping stroke: body userId={} does not match session username={}", message.userId(), sessionUsername);
            return false;
        }
        return true;
    }
}

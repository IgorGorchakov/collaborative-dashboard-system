package com.example.dashboard.api.websocket;

import com.example.dashboard.dto.StrokeMessage;
import com.example.dashboard.service.StrokeService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
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
    private final MeterRegistry meterRegistry;

    private Counter acceptedCounter;
    private Counter droppedCounter;

    @PostConstruct
    void initMetrics() {
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
        if (!dashboardId.equals(message.dashboardId())) {
            droppedCounter.increment();
            return;
        }

        UUID sessionDashboard = sessionAttributes == null
                ? null : (UUID) sessionAttributes.get(UserHandshakeInterceptor.ATTR_DASHBOARD_ID);
        String sessionUsername = sessionAttributes == null
                ? null : (String) sessionAttributes.get(UserHandshakeInterceptor.ATTR_USERNAME);

        if (sessionDashboard == null || !sessionDashboard.equals(dashboardId)) {
            log.debug("Dropping stroke: session not bound to dashboard {}", dashboardId);
            droppedCounter.increment();
            return;
        }
        if (sessionUsername == null || !sessionUsername.equals(message.userId())) {
            log.debug("Dropping stroke: body userId={} does not match session username={}",
                    message.userId(), sessionUsername);
            droppedCounter.increment();
            return;
        }

        StrokeService.PersistedStroke persisted = strokeService.append(dashboardId, message);
        messagingTemplate.convertAndSend(
                "/topic/dashboard/" + dashboardId,
                persisted.payloadJson());
        acceptedCounter.increment();
    }
}

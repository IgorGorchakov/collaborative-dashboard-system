package com.example.dashboard.websocket;

import com.example.dashboard.dto.StrokeMessage;
import com.example.dashboard.service.StrokeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class DrawingController {

    private final StrokeService strokeService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/draw/{dashboardId}")
    public void draw(@DestinationVariable UUID dashboardId, @Valid @Payload StrokeMessage message) {
        if (!dashboardId.equals(message.dashboardId())) {
            return;
        }
        StrokeService.PersistedStroke persisted = strokeService.append(dashboardId, message);
        messagingTemplate.convertAndSend(
                "/topic/dashboard/" + dashboardId,
                persisted.payloadJson());
    }
}

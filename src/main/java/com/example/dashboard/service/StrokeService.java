package com.example.dashboard.service;

import com.example.dashboard.dto.StrokeMessage;
import com.example.dashboard.model.Stroke;
import com.example.dashboard.repository.StrokeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StrokeService {

    /** Hard cap to keep the naive (all-in-one) history response bounded. */
    public static final int HISTORY_MAX = 50_000;

    private final StrokeRepository strokeRepository;
    private final ObjectMapper objectMapper;

    /**
     * Persist a stroke and return it enriched with its assigned ordinal.
     * The input {@code message} is left unchanged; clients rely on the
     * returned record to be broadcast.
     */
    @Transactional
    public PersistedStroke append(UUID dashboardId, StrokeMessage message) {
        long ordinal = strokeRepository.reserveNextOrdinal(dashboardId);
        String payload = serialize(message, ordinal);

        Stroke entity = Stroke.builder()
                .dashboardId(dashboardId)
                .ordinal(ordinal)
                .payload(payload)
                .createdAt(Instant.now())
                .build();
        strokeRepository.save(entity);

        return new PersistedStroke(ordinal, payload);
    }

    @Transactional(readOnly = true)
    public List<String> history(UUID dashboardId) {
        return strokeRepository.findByDashboardIdOrderByOrdinalAsc(dashboardId).stream()
                .limit(HISTORY_MAX)
                .map(Stroke::getPayload)
                .toList();
    }

    private String serialize(StrokeMessage message, long ordinal) {
        try {
            // Add the ordinal to the wire payload so clients can dedupe during
            // the replay-then-live overlap window (see feature 18).
            var node = objectMapper.valueToTree(message);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("ordinal", ordinal);
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize stroke", e);
        }
    }

    public record PersistedStroke(long ordinal, String payloadJson) {}
}

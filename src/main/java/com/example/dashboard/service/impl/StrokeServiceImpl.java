package com.example.dashboard.service.impl;

import com.example.dashboard.dto.StrokeMessage;
import com.example.dashboard.repository.StrokeRepository;
import com.example.dashboard.repository.model.Stroke;
import com.example.dashboard.service.StrokeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class StrokeServiceImpl implements StrokeService {

    private final StrokeRepository strokeRepository;
    private final ObjectMapper objectMapper;
    private final Timer persistTimer;

    public StrokeServiceImpl(
            StrokeRepository strokeRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.strokeRepository = strokeRepository;
        this.objectMapper = objectMapper;
        this.persistTimer = Timer.builder("dashboard.stroke.persist")
                .description("Time to persist a single stroke (ordinal reserve + insert)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Transactional
    @Override
    public String save(UUID dashboardId, StrokeMessage message) {
        return persistTimer.record(() -> doAppend(dashboardId, message));
    }

    private String doAppend(UUID dashboardId, StrokeMessage message) {
        long ordinal = strokeRepository.reserveNextOrdinal(dashboardId);
        String payload = serialize(message, ordinal);

        Stroke entity = Stroke.builder()
                .dashboardId(dashboardId)
                .ordinal(ordinal)
                .payload(payload)
                .createdAt(Instant.now())
                .build();
        strokeRepository.save(entity);

        return payload;
    }

    @Transactional(readOnly = true)
    @Override
    public void writeHistory(UUID dashboardId, OutputStream out) throws IOException {
        out.write('[');
        boolean[] first = {true};
        try (Stream<Stroke> rows = strokeRepository.streamByDashboardIdOrderByOrdinalAsc(dashboardId)
                .limit(HISTORY_MAX)) {
            rows.forEach(stroke -> {
                try {
                    if (!first[0]) {
                        out.write(',');
                    }
                    first[0] = false;
                    out.write(stroke.getPayload().getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        out.write(']');
    }

    private String serialize(StrokeMessage message, long ordinal) {
        try {
            var node = objectMapper.valueToTree(message);
            ((ObjectNode) node).put("ordinal", ordinal);
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize stroke", e);
        }
    }
}

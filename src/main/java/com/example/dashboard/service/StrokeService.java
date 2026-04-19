package com.example.dashboard.service;

import com.example.dashboard.dto.StrokeMessage;
import com.example.dashboard.repository.model.Stroke;
import com.example.dashboard.repository.StrokeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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
public class StrokeService {

    /** Hard cap to keep the naive (all-in-one) history response bounded. */
    public static final int HISTORY_MAX = 50_000;

    private final StrokeRepository strokeRepository;
    private final ObjectMapper objectMapper;
    private final Timer persistTimer;

    @Autowired
    public StrokeService(
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

    /**
     * Persist a stroke and return it enriched with its assigned ordinal.
     * The input {@code message} is left unchanged; clients rely on the
     * returned record to be broadcast.
     */
    @Transactional
    public PersistedStroke append(UUID dashboardId, StrokeMessage message) {
        return persistTimer.record(() -> doAppend(dashboardId, message));
    }

    private PersistedStroke doAppend(UUID dashboardId, StrokeMessage message) {
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

    /**
     * Streams the dashboard's persisted payloads as a JSON array directly to
     * {@code out}. Rows are pulled from the DB via a cursor and written one at
     * a time — neither the service nor the controller buffers the full response
     * in memory. Bounded by {@link #HISTORY_MAX}.
     *
     * Runs inside a read-only transaction so the JPA stream's cursor remains
     * open while we iterate; the controller invokes this from inside a
     * {@code StreamingResponseBody}.
     */
    @Transactional(readOnly = true)
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

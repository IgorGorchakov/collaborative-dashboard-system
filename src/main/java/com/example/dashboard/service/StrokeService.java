package com.example.dashboard.service;

import com.example.dashboard.dto.StrokeMessage;
import com.example.dashboard.repository.model.Stroke;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/** Public contract for stroke persistence and history retrieval. */
public interface StrokeService {

    int HISTORY_MAX = 50_000;

    /** Persist a stroke and return the serialized payload with ordinal. */
    String save(UUID dashboardId, StrokeMessage message);

    /** Stream dashboard history as JSON array directly to {@code out}. */
    void writeHistory(UUID dashboardId, OutputStream out) throws IOException;
}

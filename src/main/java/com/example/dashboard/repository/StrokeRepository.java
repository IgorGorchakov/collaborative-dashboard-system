package com.example.dashboard.repository;

import com.example.dashboard.model.Stroke;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StrokeRepository extends JpaRepository<Stroke, Long> {

    List<Stroke> findByDashboardIdOrderByOrdinalAsc(UUID dashboardId);

    /**
     * Atomic next-ordinal reservation. Relies on the {@code dashboard_stroke_counter}
     * table seeded/upserted in the same statement so ordinals are gap-free even
     * under concurrent writers. Returns the newly assigned ordinal.
     */
    @Query(value = """
            INSERT INTO dashboard_stroke_counter (dashboard_id, last_ordinal)
            VALUES (:dashboardId, 1)
            ON CONFLICT (dashboard_id)
            DO UPDATE SET last_ordinal = dashboard_stroke_counter.last_ordinal + 1
            RETURNING last_ordinal
            """, nativeQuery = true)
    long reserveNextOrdinal(@Param("dashboardId") UUID dashboardId);

    @Modifying
    @Query("delete from Stroke s where s.dashboardId = :dashboardId")
    void deleteByDashboardId(@Param("dashboardId") UUID dashboardId);

    @Modifying
    @Query(value = "UPDATE dashboard_stroke_counter SET last_ordinal = 0 WHERE dashboard_id = :dashboardId",
           nativeQuery = true)
    void resetOrdinal(@Param("dashboardId") UUID dashboardId);
}

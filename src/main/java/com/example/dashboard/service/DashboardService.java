package com.example.dashboard.service;

import com.example.dashboard.dto.CreateDashboardRequest;
import com.example.dashboard.repository.model.Dashboard;

import java.util.UUID;

/** Public contract for dashboard CRUD operations. */
public interface DashboardService {

    Dashboard create(CreateDashboardRequest request);

    Dashboard get(UUID id);

    void clear(UUID id);
}

package com.example.dashboard.repository;

import com.example.dashboard.repository.model.Dashboard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DashboardRepository extends JpaRepository<Dashboard, UUID> {
}

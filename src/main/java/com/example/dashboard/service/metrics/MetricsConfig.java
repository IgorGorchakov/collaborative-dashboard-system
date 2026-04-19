package com.example.dashboard.service.metrics;

import com.example.dashboard.repository.DashboardRepository;
import com.example.dashboard.repository.StrokeRepository;
import com.example.dashboard.service.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers domain-level gauges with Micrometer so Prometheus can scrape them.
 *
 * JVM / HTTP / HikariCP / Logback / Tomcat meters are bound automatically by
 * Spring Boot Actuator; this class only adds the things Micrometer cannot infer
 * from the framework alone.
 */
@Configuration
@RequiredArgsConstructor
public class MetricsConfig implements InitializingBean {

    private final MeterRegistry registry;
    private final DashboardRepository dashboardRepository;
    private final StrokeRepository strokeRepository;
    private final UserService userService;

    @Override
    public void afterPropertiesSet() {
        // Note: avoid the `.total` suffix — Micrometer's Prometheus naming convention
        // reserves it for counters, and gauges named with it silently don't scrape.
        registry.gauge("dashboard.count", dashboardRepository, repo -> (double) repo.count());
        registry.gauge("dashboard.strokes.count", strokeRepository, repo -> (double) repo.count());
        registry.gauge("dashboard.active_users", userService, UserService::activeUserCount);
        registry.gauge("dashboard.active_dashboards", userService, UserService::activeDashboardCount);
    }
}

package com.example.dashboard.service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class DashboardNotFoundException extends RuntimeException {
    public DashboardNotFoundException(UUID id) {
        super("Dashboard not found: " + id);
    }
}

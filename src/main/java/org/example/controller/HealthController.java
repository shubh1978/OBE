package org.example.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Lightweight health-check endpoint.
 * UptimeRobot / any uptime monitor should ping GET /health
 * This also keeps the Render free-tier service alive (no sleep).
 */
@RestController
public class HealthController {

    @GetMapping({"/", "/health", "/ping"})
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "timestamp", Instant.now().toString(),
            "service", "OBE Analytics"
        ));
    }
}

package org.example.controller;

import org.example.service.AttainmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/attainment")
@RequiredArgsConstructor
public class AttainmentController {

    private final AttainmentService attainmentService;

    /**
     * GET /api/attainment/co/{courseId}?specializationId=X
     * CO attainment scoped to a specialization when specializationId is provided.
     */
    @GetMapping("/co/{courseId}")
    public ResponseEntity<Map<String, Double>> getCOAttainment(
            @PathVariable Long courseId,
            @RequestParam(required = false) Long specializationId) {
        Map<String, Double> result = attainmentService.calculateCOAttainment(courseId, specializationId);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/attainment/co-levels/{courseId}?specializationId=X
     * CO attainment levels (0-3) scoped to a specialization.
     */
    @GetMapping("/co-levels/{courseId}")
    public ResponseEntity<Map<String, Integer>> getCOLevels(
            @PathVariable Long courseId,
            @RequestParam(required = false) Long specializationId) {
        Map<String, Integer> result = attainmentService.getCOLevels(courseId, specializationId);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/attainment/po/{courseId}?specializationId=X
     * PO attainment (0-3 scale) scoped to a specialization.
     */
    @GetMapping("/po/{courseId}")
    public ResponseEntity<Map<String, Double>> getPOAttainment(
            @PathVariable Long courseId,
            @RequestParam(required = false) Long specializationId) {
        Map<String, Double> result = attainmentService.calculatePOAttainment(courseId, specializationId);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/attainment/pso/{courseId}?specializationId=X
     * PSO attainment (0-3 scale) scoped to a specialization.
     */
    @GetMapping("/pso/{courseId}")
    public ResponseEntity<Map<String, Double>> getPSOAttainment(
            @PathVariable Long courseId,
            @RequestParam(required = false) Long specializationId) {
        Map<String, Double> result = attainmentService.calculatePSOAttainment(courseId, specializationId);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/attainment/at-risk/{courseId}?specializationId=X
     * Count of at-risk students (< 40% overall marks) for a course,
     * optionally scoped to a specialization.
     */
    @GetMapping("/at-risk/{courseId}")
    public ResponseEntity<Map<String, Object>> getAtRiskCount(
            @PathVariable Long courseId,
            @RequestParam(required = false) Long specializationId) {
        int count = attainmentService.getAtRiskCount(courseId, specializationId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("courseId", courseId);
        result.put("atRiskCount", count);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/attainment/student-count/{courseId}
     */
    @GetMapping("/student-count/{courseId}")
    public ResponseEntity<Map<String, Object>> getStudentCount(
            @PathVariable Long courseId,
            @RequestParam(required = false) Long specializationId) {
        int count = (specializationId != null)
                ? attainmentService.getStudentCountBySpec(courseId, specializationId)
                : attainmentService.getStudentCount(courseId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("courseId", courseId);
        result.put("studentCount", count);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/attainment/co-by-exam-type/{courseId}
     */
    @GetMapping("/co-by-exam-type/{courseId}")
    public ResponseEntity<Map<String, Map<String, Double>>> getCOAttainmentByExamType(
            @PathVariable Long courseId) {
        return ResponseEntity.ok(attainmentService.calculateCOAttainmentByExamType(courseId));
    }

    /**
     * GET /api/attainment/students/{courseId}
     */
    @GetMapping("/students/{courseId}")
    public ResponseEntity<Map<Long, Map<String, Double>>> getStudentAttainments(
            @PathVariable Long courseId) {
        return ResponseEntity.ok(attainmentService.calculateIndividualStudentCOAttainment(courseId));
    }

    /**
     * GET /api/attainment/co-po-mapping/{courseId}
     */
    @GetMapping("/co-po-mapping/{courseId}")
    public ResponseEntity<List<Map<String, Object>>> getCOPOMapping(@PathVariable Long courseId) {
        return ResponseEntity.ok(attainmentService.getCOPOMappingMatrix(courseId));
    }

    /**
     * GET /api/attainment/co-pso-mapping/{courseId}
     */
    @GetMapping("/co-pso-mapping/{courseId}")
    public ResponseEntity<List<Map<String, Object>>> getCOPSOMapping(@PathVariable Long courseId) {
        return ResponseEntity.ok(attainmentService.getCOPSOMappingMatrix(courseId));
    }

    /**
     * GET /api/attainment/report/{courseId}
     */
    @GetMapping("/report/{courseId}")
    public ResponseEntity<Map<String, Object>> getAttainmentReport(@PathVariable Long courseId) {
        return ResponseEntity.ok(attainmentService.getAttainmentReport(courseId));
    }

    /**
     * GET /api/attainment/endpoints
     */
    @GetMapping("/endpoints")
    public ResponseEntity<Map<String, Object>> getEndpoints() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("endpoints", new LinkedHashMap<String, String>() {{
            put("GET /api/attainment/co/{courseId}?specializationId=", "CO attainment % scoped to spec");
            put("GET /api/attainment/co-levels/{courseId}?specializationId=", "CO levels 0-3 scoped to spec");
            put("GET /api/attainment/po/{courseId}?specializationId=", "PO attainment 0-3 scoped to spec");
            put("GET /api/attainment/pso/{courseId}?specializationId=", "PSO attainment 0-3 scoped to spec");
            put("GET /api/attainment/at-risk/{courseId}?specializationId=", "At-risk student count");
            put("GET /api/attainment/student-count/{courseId}?specializationId=", "Distinct student count");
            put("GET /api/attainment/co-by-exam-type/{courseId}", "CO by mid/end term");
            put("GET /api/attainment/students/{courseId}", "Individual student CO scores");
        }});
        return ResponseEntity.ok(endpoints);
    }
}

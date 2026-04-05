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
     * Get overall CO attainment for a course (combined mid+end term)
     * Range: 0-100 (percentage of students achieving ≥40%)
     * 
     * @param courseId Course ID
     * @return Map of CO code -> Attainment percentage
     */
    @GetMapping("/co/{courseId}")
    public ResponseEntity<Map<String, Double>> getCOAttainment(@PathVariable Long courseId) {
        Map<String, Double> coAttainments = attainmentService.calculateCOAttainment(courseId);
        return ResponseEntity.ok(coAttainments);
    }

    /**
     * Get CO attainment separated by exam type (mid-term vs end-term)
     * Useful for tracking progress throughout the course
     * 
     * @param courseId Course ID
     * @return Map with mid_term and end_term CO attainments
     */
    @GetMapping("/co-by-exam-type/{courseId}")
    public ResponseEntity<Map<String, Map<String, Double>>> getCOAttainmentByExamType(
            @PathVariable Long courseId) {
        Map<String, Map<String, Double>> byExamType = 
            attainmentService.calculateCOAttainmentByExamType(courseId);
        return ResponseEntity.ok(byExamType);
    }

    /**
     * Get individual student CO attainment scores (combined mid+end term)
     * Useful for identifying struggling students
     * 
     * @param courseId Course ID
     * @return Map of Student ID -> (CO code -> Percentage)
     */
    @GetMapping("/students/{courseId}")
    public ResponseEntity<Map<Long, Map<String, Double>>> getStudentAttainments(
            @PathVariable Long courseId) {
        Map<Long, Map<String, Double>> studentAttainments = 
            attainmentService.calculateIndividualStudentCOAttainment(courseId);
        return ResponseEntity.ok(studentAttainments);
    }

    /**
     * Get PO (Program Outcome) attainment
     * Range: 0-3 (weighted average of CO attainments)
     * 
     * @param courseId Course ID
     * @return Map of PO code -> Score (0-3)
     */
    @GetMapping("/po/{courseId}")
    public ResponseEntity<Map<String, Double>> getPOAttainment(@PathVariable Long courseId) {
        Map<String, Double> poAttainments = attainmentService.calculatePOAttainment(courseId);
        return ResponseEntity.ok(poAttainments);
    }

    /**
     * Get PSO (Program Specialization Outcome) attainment
     * Range: 0-3 (weighted average of CO attainments per course)
     * 
     * @param courseId Course ID
     * @return Map of PSO code -> Score (0-3)
     */
    @GetMapping("/pso/{courseId}")
    public ResponseEntity<Map<String, Double>> getPSOAttainment(@PathVariable Long courseId) {
        Map<String, Double> psoAttainments = attainmentService.calculatePSOAttainment(courseId);
        return ResponseEntity.ok(psoAttainments);
    }

    /**
     * Get comprehensive attainment report for a course
     * Includes: CO (overall + by exam type + individual), PO, PSO, configuration
     * 
     * @param courseId Course ID
     * @return Comprehensive attainment report
     */
    @GetMapping("/report/{courseId}")
    public ResponseEntity<Map<String, Object>> getAttainmentReport(@PathVariable Long courseId) {
        Map<String, Object> report = attainmentService.getAttainmentReport(courseId);
        return ResponseEntity.ok(report);
    }

    /**
    /**
     * Get CO-PO mapping matrix for a course
     * Returns list of objects with CO code and PO weights
     * 
     * @param courseId Course ID
     * @return List of CO-PO mapping entries
     */
    @GetMapping("/co-po-mapping/{courseId}")
    public ResponseEntity<List<Map<String, Object>>> getCOPOMapping(@PathVariable Long courseId) {
        List<Map<String, Object>> matrix = attainmentService.getCOPOMappingMatrix(courseId);
        return ResponseEntity.ok(matrix);
    }

    /**
     * Get CO-PSO mapping matrix for a course
     * Returns list of objects with CO code and PSO weights
     * 
     * @param courseId Course ID
     * @return List of CO-PSO mapping entries
     */
    @GetMapping("/co-pso-mapping/{courseId}")
    public ResponseEntity<List<Map<String, Object>>> getCOPSOMapping(@PathVariable Long courseId) {
        List<Map<String, Object>> matrix = attainmentService.getCOPSOMappingMatrix(courseId);
        return ResponseEntity.ok(matrix);
    }

    /**
     * Get all available attainment endpoints
     * @return List of available endpoints and their descriptions
     */
    @GetMapping("/endpoints")
    public ResponseEntity<Map<String, Object>> getEndpoints() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("endpoints", new LinkedHashMap<String, String>() {{
            put("GET /api/attainment/co/{courseId}", "CO attainment overall (%)");
            put("GET /api/attainment/co-by-exam-type/{courseId}", "CO attainment by exam type");
            put("GET /api/attainment/students/{courseId}", "Individual student CO scores");
            put("GET /api/attainment/po/{courseId}", "PO attainment (0-3 scale)");
            put("GET /api/attainment/pso/{courseId}", "PSO attainment (0-3 scale)");
            put("GET /api/attainment/co-po-mapping/{courseId}", "CO-PO mapping matrix");
            put("GET /api/attainment/co-pso-mapping/{courseId}", "CO-PSO mapping matrix");
            put("GET /api/attainment/report/{courseId}", "Complete attainment report");
            put("POST /api/marks/upload-zip", "Upload marks from SOET ZIP");
        }});
        return ResponseEntity.ok(endpoints);
    }
}


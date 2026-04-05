package org.example.controller;

import org.example.entity.*;
import org.example.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/diagnostics")
@RequiredArgsConstructor
public class DiagnosticsController {

    private final StudentMarkRepository studentMarkRepository;
    private final QuestionCOMappingRepository questionCOMappingRepository;
    private final CO_PO_MappingRepository coPOMappingRepository;
    private final COPSORepository coPSORepository;
    private final CourseRepository courseRepository;
    private final CORepository coRepository;
    private final PORepository poRepository;
    private final PSORepository psoRepository;
    private final ProgramRepository programRepository;

    /**
     * Diagnose why attainment is not calculating
     */
    @GetMapping("/attainment-debug/{courseId}")
    public ResponseEntity<Map<String, Object>> debugAttainment(@PathVariable Long courseId) {
        Map<String, Object> debug = new LinkedHashMap<>();

        // Check course exists
        Course course = courseRepository.findById(courseId).orElse(null);
        debug.put("course", course != null ? course.getCourseCode() : "NOT FOUND");

        // Check StudentMark records
        List<StudentMark> marks = studentMarkRepository.findByCourseId(courseId);
        debug.put("total_student_marks", marks.size());
        
        if (!marks.isEmpty()) {
            StudentMark sample = marks.get(0);
            debug.put("sample_mark", new LinkedHashMap<String, Object>() {{
                put("student", sample.getStudent() != null ? sample.getStudent().getEnrollmentNumber() : null);
                put("question", sample.getQuestion());
                put("marks", sample.getMarks());
                put("max_marks", sample.getMaxMarks());
                put("exam_type", sample.getExamType());
            }});
        }

        // Check QuestionCOMapping
        List<QuestionCOMapping> mappings = questionCOMappingRepository.findByCourseId(courseId);
        debug.put("total_question_co_mappings", mappings.size());
        
        if (!mappings.isEmpty()) {
            List<Map<String, Object>> sampleMappings = new ArrayList<>();
            mappings.stream().limit(5).forEach(m -> {
                sampleMappings.add(new LinkedHashMap<String, Object>() {{
                    put("question_label", m.getQuestionLabel());
                    put("co_code", m.getCo() != null ? m.getCo().getCode() : null);
                    put("max_marks", m.getMaxMarks());
                }});
            });
            debug.put("sample_mappings", sampleMappings);
        } else {
            debug.put("warning", "⚠️ NO QUESTION-CO MAPPINGS FOUND - This is why attainment is empty!");
        }

        // Check CO-PO Mappings
        List<COPOMap> poMappings = coPOMappingRepository.findByCoCourseId(courseId);
        debug.put("total_co_po_mappings", poMappings.size());

        // Check CO-PSO Mappings
        List<CO> cos = coRepository.findByCourseId(courseId);
        debug.put("total_cos_in_course", cos.size());
        
        int psoCount = 0;
        for (CO co : cos) {
            psoCount += coPSORepository.findByCoId(co.getId()).size();
        }
        debug.put("total_co_pso_mappings", psoCount);

        // Matching analysis
        Map<String, Object> matchingAnalysis = new LinkedHashMap<>();
        if (!marks.isEmpty() && !mappings.isEmpty()) {
            Set<String> markQuestions = new HashSet<>();
            marks.forEach(m -> markQuestions.add(m.getQuestion()));
            
            Set<String> mappingQuestions = new HashSet<>();
            mappings.forEach(m -> mappingQuestions.add(m.getQuestionLabel()));
            
            matchingAnalysis.put("questions_in_marks", markQuestions);
            matchingAnalysis.put("questions_in_mappings", mappingQuestions);
            
            Set<String> matched = new HashSet<>(markQuestions);
            matched.retainAll(mappingQuestions);
            matchingAnalysis.put("matched_questions", matched.size());
            matchingAnalysis.put("unmatched_mark_questions", markQuestions.size() - matched.size());
            matchingAnalysis.put("unmatched_mapping_questions", mappingQuestions.size() - matched.size());
        }
        debug.put("matching_analysis", matchingAnalysis);

        return ResponseEntity.ok(debug);
    }

    /**
     * Check all courses and their mapping status
     */
    @GetMapping("/courses-mapping-status")
    public ResponseEntity<List<Map<String, Object>>> courseMappingStatus() {
        List<Map<String, Object>> status = new ArrayList<>();
        
        List<Course> courses = courseRepository.findAll();
        
        for (Course course : courses) {
            List<StudentMark> marks = studentMarkRepository.findByCourseId(course.getId());
            List<QuestionCOMapping> mappings = questionCOMappingRepository.findByCourseId(course.getId());
            
            if (!marks.isEmpty()) {
                status.add(new LinkedHashMap<String, Object>() {{
                    put("course_code", course.getCourseCode());
                    put("course_id", course.getId());
                    put("student_marks", marks.size());
                    put("question_co_mappings", mappings.size());
                    put("status", mappings.isEmpty() ? "⚠️ MISSING MAPPINGS" : "✅ OK");
                }});
            }
        }
        
        return ResponseEntity.ok(status);
    }

    /**
     * Get sample data from database for inspection
     */
    @GetMapping("/sample-data/{courseId}")
    public ResponseEntity<Map<String, Object>> sampleData(@PathVariable Long courseId) {
        Map<String, Object> data = new LinkedHashMap<>();
        
        // ...existing sample data methods...
        
        return ResponseEntity.ok(data);
    }

    /**
     * Diagnose PO mapping issues
     */
    @GetMapping("/po-mapping-debug/{courseId}")
    public ResponseEntity<Map<String, Object>> debugPOMappings(@PathVariable Long courseId) {
        Map<String, Object> debug = new LinkedHashMap<>();

        Course course = courseRepository.findById(courseId).orElse(null);
        if (course == null) {
            debug.put("error", "Course not found");
            return ResponseEntity.ok(debug);
        }

        debug.put("course_code", course.getCourseCode());
        debug.put("program_id", course.getProgram() != null ? course.getProgram().getId() : "NULL");
        debug.put("program_name", course.getProgram() != null ? course.getProgram().getName() : "NULL");

        // Check POs exist for program
        if (course.getProgram() != null) {
            List<PO> pos = poRepository.findByProgram(course.getProgram());
            debug.put("total_pos_for_program", pos.size());
            List<Map<String, Object>> poList = new ArrayList<>();
            pos.forEach(po -> poList.add(new LinkedHashMap<String, Object>() {{
                put("id", po.getId());
                put("code", po.getCode());
                put("description", po.getDescription() != null ? po.getDescription().substring(0, Math.min(50, po.getDescription().length())) : "");
            }}));
            debug.put("pos", poList);
        }

        // Check CO-PO mappings
        List<COPOMap> poMappings = coPOMappingRepository.findByCoCourseId(courseId);
        debug.put("total_co_po_mappings", poMappings.size());
        
        List<Map<String, Object>> poMappingsList = new ArrayList<>();
        poMappings.forEach(m -> poMappingsList.add(new LinkedHashMap<String, Object>() {{
            put("co_code", m.getCo() != null ? m.getCo().getCode() : "NULL");
            put("po_code", m.getPo() != null ? m.getPo().getCode() : "NULL");
            put("weight", m.getWeight());
        }}));
        debug.put("co_po_mappings", poMappingsList);

        // Get COs
        List<CO> cos = coRepository.findByCourseId(courseId);
        debug.put("total_cos", cos.size());

        if (poMappings.isEmpty() && !cos.isEmpty()) {
            debug.put("warning", "⚠️ NO CO-PO MAPPINGS FOUND - This is why PO attainment is 0%!");
        }

        return ResponseEntity.ok(debug);
    }

    /**
     * Diagnose PSO mapping issues
     */
    @GetMapping("/pso-mapping-debug/{courseId}")
    public ResponseEntity<Map<String, Object>> debugPSOMappings(@PathVariable Long courseId) {
        Map<String, Object> debug = new LinkedHashMap<>();

        Course course = courseRepository.findById(courseId).orElse(null);
        if (course == null) {
            debug.put("error", "Course not found");
            return ResponseEntity.ok(debug);
        }

        debug.put("course_code", course.getCourseCode());
        debug.put("program_id", course.getProgram() != null ? course.getProgram().getId() : "NULL");
        debug.put("program_name", course.getProgram() != null ? course.getProgram().getName() : "NULL");
        debug.put("specialization_id", course.getSpecialization() != null ? course.getSpecialization().getId() : "NULL");

        // Check PSOs exist for program
        if (course.getProgram() != null) {
            List<PSO> psos = psoRepository.findByProgram(course.getProgram());
            debug.put("total_psos_for_program", psos.size());
            List<Map<String, Object>> psoList = new ArrayList<>();
            psos.forEach(pso -> psoList.add(new LinkedHashMap<String, Object>() {{
                put("id", pso.getId());
                put("code", pso.getCode());
                put("program_id", pso.getProgram() != null ? pso.getProgram().getId() : "NULL");
                put("specialization_id", pso.getSpecialization() != null ? pso.getSpecialization().getId() : "NULL");
                put("description", pso.getDescription() != null ? pso.getDescription().substring(0, Math.min(50, pso.getDescription().length())) : "");
            }}));
            debug.put("psos", psoList);
        }

        // Check CO-PSO mappings
        List<CO> cos = coRepository.findByCourseId(courseId);
        debug.put("total_cos", cos.size());
        
        Map<String, List<Map<String, Object>>> coToPsoMappings = new LinkedHashMap<>();
        int totalPSOMappings = 0;
        
        for (CO co : cos) {
            List<COPSOMapping> psoMappings = coPSORepository.findByCoId(co.getId());
            List<Map<String, Object>> mappingsList = new ArrayList<>();
            
            for (COPSOMapping m : psoMappings) {
                mappingsList.add(new LinkedHashMap<String, Object>() {{
                    put("pso_code", m.getPso() != null ? m.getPso().getCode() : "NULL");
                    put("pso_id", m.getPso() != null ? m.getPso().getId() : "NULL");
                    put("weight", m.getWeight());
                }});
                totalPSOMappings++;
            }
            
            coToPsoMappings.put(co.getCode(), mappingsList);
        }
        
        debug.put("total_co_pso_mappings", totalPSOMappings);
        debug.put("co_pso_mappings_by_co", coToPsoMappings);

        if (totalPSOMappings == 0 && !cos.isEmpty()) {
            debug.put("warning", "⚠️ NO CO-PSO MAPPINGS FOUND - This is why PSO attainment is 0%!");
        }

        return ResponseEntity.ok(debug);
    }

    /**
     * Complete mapping summary for all courses
     */
    @GetMapping("/mapping-summary")
    public ResponseEntity<List<Map<String, Object>>> getMappingSummary() {
        List<Map<String, Object>> summary = new ArrayList<>();

        for (Course course : courseRepository.findAll()) {
            List<StudentMark> marks = studentMarkRepository.findByCourseId(course.getId());
            
            if (marks.isEmpty()) continue;

            List<CO> cos = coRepository.findByCourseId(course.getId());
            List<COPOMap> poMappings = coPOMappingRepository.findByCoCourseId(course.getId());
            
            int totalPsoMappings = 0;
            for (CO co : cos) {
                totalPsoMappings += coPSORepository.findByCoId(co.getId()).size();
            }

            final int psoMappingsCount = totalPsoMappings;
            
            summary.add(new LinkedHashMap<String, Object>() {{
                put("course_code", course.getCourseCode());
                put("course_id", course.getId());
                put("student_marks", marks.size());
                put("cos", cos.size());
                put("po_mappings", poMappings.size());
                put("pso_mappings", psoMappingsCount);
                put("po_status", poMappings.isEmpty() ? "❌ MISSING" : "✅ OK");
                put("pso_status", psoMappingsCount == 0 ? "❌ MISSING" : "✅ OK");
            }});
        }

        return ResponseEntity.ok(summary);
    }
}


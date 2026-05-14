package org.example.controller;

import org.example.entity.*;
import org.example.repository.*;
import org.example.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;
    private final CourseRepository courseRepository;
    private final CORepository coRepository;
    private final CO_PO_MappingRepository copoRepository;
    private final COPSORepository copsoRepository;
    private final StudentMarkRepository studentMarkRepository;

    @PostMapping
    public Course createCourse(@RequestParam Long batchId,
            @RequestParam Long programId,
            @RequestParam Long specializationId,
            @RequestParam Long semesterId,
            @RequestBody Course course) {
        return courseService.create(batchId, programId, specializationId, semesterId, course);
    }

    @GetMapping
    public List<Course> getAllCourses() {
        return courseService.getAll();
    }

    /**
     * GET /api/courses/complete
     * Returns all courses with their CO, PO, and PSO mappings
     * Useful for frontend dashboard showing complete course structure
     */
    @GetMapping("/complete")
    public ResponseEntity<?> getAllCoursesComplete() {
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (Course course : courseRepository.findAll()) {
            Map<String, Object> courseMap = new LinkedHashMap<>();
            courseMap.put("id", course.getId());
            courseMap.put("code", course.getCourseCode());
            courseMap.put("name", course.getCourseName());
            courseMap.put("semester", course.getSemester() != null ? course.getSemester().getNumber() : null);
            courseMap.put("program", course.getProgram() != null ? course.getProgram().getName() : null);
            courseMap.put("specialization", course.getSpecialization() != null ? course.getSpecialization().getName() : null);
            courseMap.put("batch_year", course.getBatch() != null ? course.getBatch().getStartYear() : null);
            
            // Count marks and students
            List<StudentMark> marks = studentMarkRepository.findByCourse(course);
            long studentCount = marks.stream().map(m -> m.getStudent().getId()).distinct().count();
            courseMap.put("studentCount", (int) studentCount);
            courseMap.put("markCount", marks.size());
            
            // Get COs for this course
            List<CO> cos = coRepository.findByCourse(course);
            cos.sort(Comparator.comparingInt(co -> {
                try { return Integer.parseInt(co.getCode().replaceAll("\\D+", "")); }
                catch (Exception e) { return 999; }
            }));
            
            List<Map<String, Object>> coList = new ArrayList<>();
            for (CO co : cos) {
                Map<String, Object> coMap = new LinkedHashMap<>();
                coMap.put("id", co.getId());
                coMap.put("code", co.getCode());
                coMap.put("description", co.getDescription());
                
                // Get PO mappings for this CO
                List<COPOMap> poMappings = copoRepository.findByCoId(co.getId());
                List<Map<String, Object>> poMappingList = new ArrayList<>();
                for (COPOMap pm : poMappings) {
                    Map<String, Object> pmMap = new LinkedHashMap<>();
                    pmMap.put("po_code", pm.getPo().getCode());
                    pmMap.put("po_description", pm.getPo().getDescription());
                    pmMap.put("weight", pm.getWeight());
                    poMappingList.add(pmMap);
                }
                coMap.put("po_mappings", poMappingList);
                
                // Get PSO mappings for this CO
                List<COPSOMapping> psoMappings = copsoRepository.findByCoId(co.getId());
                List<Map<String, Object>> psoMappingList = new ArrayList<>();
                for (COPSOMapping pm : psoMappings) {
                    Map<String, Object> pmMap = new LinkedHashMap<>();
                    pmMap.put("pso_code", pm.getPso().getCode());
                    pmMap.put("pso_description", pm.getPso().getDescription());
                    pmMap.put("weight", pm.getWeight());
                    psoMappingList.add(pmMap);
                }
                coMap.put("pso_mappings", psoMappingList);
                
                coList.add(coMap);
            }
            courseMap.put("cos", coList);
            result.add(courseMap);
        }
        
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/courses/{courseId}/mappings
     * Returns detailed CO-PO and CO-PSO mappings for a specific course
     */
    @GetMapping("/{courseId}/mappings")
    public ResponseEntity<?> getCourseMappings(@PathVariable Long courseId) {
        Course course = courseRepository.findById(courseId).orElse(null);
        if (course == null) {
            return ResponseEntity.ok(Map.of("error", "Course not found"));
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("courseId", courseId);
        result.put("courseCode", course.getCourseCode());
        result.put("courseName", course.getCourseName());
        
        List<CO> cos = coRepository.findByCourse(course);
        cos.sort(Comparator.comparingInt(co -> {
            try { return Integer.parseInt(co.getCode().replaceAll("\\D+", "")); }
            catch (Exception e) { return 999; }
        }));
        
        List<Map<String, Object>> coList = new ArrayList<>();
        for (CO co : cos) {
            Map<String, Object> coMap = new LinkedHashMap<>();
            coMap.put("co_code", co.getCode());
            coMap.put("co_description", co.getDescription());
            
            List<COPOMap> poMappings = copoRepository.findByCoId(co.getId());
            coMap.put("po_mappings", poMappings.stream().collect(Collectors.toMap(
                pm -> pm.getPo().getCode(),
                pm -> Map.of("description", pm.getPo().getDescription(), "weight", pm.getWeight())
            )));
            
            List<COPSOMapping> psoMappings = copsoRepository.findByCoId(co.getId());
            coMap.put("pso_mappings", psoMappings.stream().collect(Collectors.toMap(
                pm -> pm.getPso().getCode(),
                pm -> Map.of("description", pm.getPso().getDescription(), "weight", pm.getWeight())
            )));
            
            coList.add(coMap);
        }
        result.put("co_details", coList);
        
        return ResponseEntity.ok(result);
    }
}
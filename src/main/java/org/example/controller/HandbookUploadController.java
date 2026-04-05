package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.entity.*;
import org.example.repository.*;
import org.example.service.HandbookParserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/handbook")
@RequiredArgsConstructor
public class HandbookUploadController {

    private final ProgramRepository programRepository;
    private final SpecializationRepository specializationRepository;
    private final BatchRepository batchRepository;
    private final SemesterRepository semesterRepository;
    private final CourseRepository courseRepository;
    private final CORepository coRepository;
    private final PORepository poRepository;
    private final CO_PO_MappingRepository copoRepository;
    private final PSORepository psoRepository;
    private final COPSORepository copsoRepository;

    private final HandbookParserService handbookParserService;

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file) throws Exception {

        handbookParserService.parseHandbook(file);

        return ResponseEntity.ok("Handbook processed successfully");
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getFullSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        List<Program> programs = programRepository.findAll();
        List<Map<String, Object>> programList = new ArrayList<>();

        for (Program program : programs) {
            Map<String, Object> programMap = new LinkedHashMap<>();
            programMap.put("program_id", program.getId());
            programMap.put("program_name", program.getName());

            // POs for this program
            List<PO> pos = poRepository.findByProgram(program);
            List<Map<String, Object>> poList = pos.stream().map(po -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", po.getCode());
                m.put("description", po.getDescription());
                return m;
            }).collect(Collectors.toList());
            programMap.put("program_outcomes_PO", poList);

            // Specializations
            List<Specialization> specs = specializationRepository.findByProgram(program);
            List<Map<String, Object>> specList = new ArrayList<>();

            for (Specialization spec : specs) {
                Map<String, Object> specMap = new LinkedHashMap<>();
                specMap.put("specialization_id", spec.getId());
                specMap.put("specialization_name", spec.getName());

                // Batches
                List<Batch> batches = batchRepository.findBySpecialization(spec);
                List<Map<String, Object>> batchList = new ArrayList<>();

                for (Batch batch : batches) {
                    Map<String, Object> batchMap = new LinkedHashMap<>();
                    batchMap.put("batch_id", batch.getId());
                    batchMap.put("batch", batch.getStartYear() + " – " + batch.getEndYear());

                    // Semesters
                    List<Semester> semesters = semesterRepository.findByBatch(batch);
                    semesters.sort(Comparator.comparingInt(Semester::getNumber));

                    List<Map<String, Object>> semesterList = new ArrayList<>();

                    for (Semester semester : semesters) {
                        Map<String, Object> semMap = new LinkedHashMap<>();
                        semMap.put("semester_id", semester.getId());
                        semMap.put("semester_number", semester.getNumber());

                        // Courses
                        List<Course> courses = courseRepository.findBySemester(semester);
                        List<Map<String, Object>> courseList = new ArrayList<>();

                        for (Course course : courses) {
                            Map<String, Object> courseMap = new LinkedHashMap<>();
                            courseMap.put("course_code", course.getCourseCode());
                            courseMap.put("course_name", course.getCourseName());

                            // COs
                            List<CO> cos = coRepository.findByCourse(course);
                            List<Map<String, Object>> coList = new ArrayList<>();

                            for (CO co : cos) {
                                Map<String, Object> coMap = new LinkedHashMap<>();
                                coMap.put("co_code", co.getCode());
                                coMap.put("description", co.getDescription());

                                // CO-PO Mappings
                                List<COPOMap> copoMaps = copoRepository.findByCo(co);
                                if (!copoMaps.isEmpty()) {
                                    Map<String, Integer> copoMapping = new LinkedHashMap<>();
                                    copoMaps.stream()
                                            .sorted(Comparator.comparing(m -> m.getPo().getCode()))
                                            .forEach(m -> copoMapping.put(m.getPo().getCode(), m.getWeight()));
                                    coMap.put("co_po_mapping", copoMapping);
                                }

                                // CO-PSO Mappings
                                List<COPSOMapping> copsomaps = copsoRepository.findByCo(co);
                                if (!copsomaps.isEmpty()) {
                                    Map<String, Integer> copsoMapping = new LinkedHashMap<>();
                                    copsomaps.stream()
                                            .sorted(Comparator.comparing(m -> m.getPso().getCode()))
                                            .forEach(m -> copsoMapping.put(m.getPso().getCode(), m.getWeight()));
                                    coMap.put("co_pso_mapping", copsoMapping);
                                }

                                coList.add(coMap);
                            }
                            courseMap.put("course_outcomes_CO", coList);
                            courseList.add(courseMap);
                        }
                        semMap.put("courses", courseList);
                        semesterList.add(semMap);
                    }
                    batchMap.put("semesters", semesterList);
                    batchList.add(batchMap);
                }
                specMap.put("batches", batchList);

                // PSOs for this specialization
                List<PSO> psos = psoRepository.findBySpecialization(spec);
                List<Map<String, Object>> psoList = psos.stream().map(pso -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", pso.getCode());
                    m.put("description", pso.getDescription());
                    return m;
                }).collect(Collectors.toList());
                specMap.put("program_specific_outcomes_PSO", psoList);

                specList.add(specMap);
            }
            programMap.put("specializations", specList);
            programList.add(programMap);
        }

        summary.put("total_programs", programs.size());
        summary.put("programs", programList);
        return ResponseEntity.ok(summary);
    }

    // ─────────────────────────────────────────────
    // 2. QUICK STATS  GET /obe-summary/stats
    // ─────────────────────────────────────────────
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("programs",        programRepository.count());
        stats.put("specializations", specializationRepository.count());
        stats.put("batches",         batchRepository.count());
        stats.put("semesters",       semesterRepository.count());
        stats.put("courses",         courseRepository.count());
        stats.put("course_outcomes", coRepository.count());
        stats.put("program_outcomes",poRepository.count());
        stats.put("PSOs",            psoRepository.count());
        stats.put("CO_PO_mappings",  copoRepository.count());
        stats.put("CO_PSO_mappings", copsoRepository.count());
        return ResponseEntity.ok(stats);
    }

    // ─────────────────────────────────────────────
    // 3. COURSES BY SEMESTER  GET /obe-summary/semester/{number}
    // ─────────────────────────────────────────────
    @GetMapping("/semester/{number}")
    public ResponseEntity<?> getSemesterDetails(@PathVariable int number) {
        List<Semester> semesters = semesterRepository.findByNumber(number);
        if (semesters.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "No data found for Semester " + number));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Semester semester : semesters) {
            Map<String, Object> semMap = new LinkedHashMap<>();
            semMap.put("semester_number", semester.getNumber());
            semMap.put("batch", semester.getBatch().getStartYear() + " – " + semester.getBatch().getEndYear());

            List<Course> courses = courseRepository.findBySemester(semester);
            List<Map<String, Object>> courseList = new ArrayList<>();

            for (Course course : courses) {
                Map<String, Object> courseMap = new LinkedHashMap<>();
                courseMap.put("course_code", course.getCourseCode());
                courseMap.put("course_name", course.getCourseName());

                List<CO> cos = coRepository.findByCourse(course);
                List<Map<String, Object>> coList = cos.stream().map(co -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("co", co.getCode());
                    m.put("description", co.getDescription());
                    return m;
                }).collect(Collectors.toList());
                courseMap.put("course_outcomes", coList);
                courseList.add(courseMap);
            }
            semMap.put("courses", courseList);
            result.add(semMap);
        }
        return ResponseEntity.ok(result);
    }

    // ─────────────────────────────────────────────
    // 4. COURSE DETAIL  GET /obe-summary/course/{code}
    // ─────────────────────────────────────────────
    @GetMapping("/course/{code}")
    public ResponseEntity<?> getCourseDetail(@PathVariable String code) {
        Course course = courseRepository.findByCourseCode(code).orElse(null);
        if (course == null) {
            return ResponseEntity.ok(Map.of("message", "Course " + code + " not found"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("course_code", course.getCourseCode());
        result.put("course_name", course.getCourseName());
        result.put("semester", course.getSemester().getNumber());
        result.put("batch", course.getBatch().getStartYear() + " – " + course.getBatch().getEndYear());
        result.put("specialization", course.getSpecialization().getName());
        result.put("program", course.getProgram().getName());

        List<CO> cos = coRepository.findByCourse(course);

        // Build CO-PO matrix table
        List<PO> pos = poRepository.findByProgram(course.getProgram());
        pos.sort(Comparator.comparing(PO::getCode));

        List<String> poHeaders = pos.stream().map(PO::getCode).collect(Collectors.toList());
        result.put("co_po_matrix_headers", poHeaders);

        List<Map<String, Object>> coRows = new ArrayList<>();
        for (CO co : cos) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("co", co.getCode());
            row.put("description", co.getDescription());

            List<COPOMap> mappings = copoRepository.findByCo(co);
            Map<String, Integer> mappingByPO = new HashMap<>();
            mappings.forEach(m -> mappingByPO.put(m.getPo().getCode(), m.getWeight()));

            for (String poCode : poHeaders) {
                row.put(poCode, mappingByPO.getOrDefault(poCode, 0));
            }
            coRows.add(row);
        }
        result.put("course_outcomes_with_co_po_matrix", coRows);

        // CO-PSO matrix
        List<PSO> psos = psoRepository.findBySpecialization(course.getSpecialization());
        if (!psos.isEmpty()) {
            psos.sort(Comparator.comparing(PSO::getCode));
            List<String> psoHeaders = psos.stream().map(PSO::getCode).collect(Collectors.toList());
            result.put("co_pso_matrix_headers", psoHeaders);

            List<Map<String, Object>> psoRows = new ArrayList<>();
            for (CO co : cos) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("co", co.getCode());
                List<COPSOMapping> psoMappings = copsoRepository.findByCo(co);
                Map<String, Integer> mappingByPSO = new HashMap<>();
                psoMappings.forEach(m -> mappingByPSO.put(m.getPso().getCode(), m.getWeight()));
                for (String psoCode : psoHeaders) {
                    row.put(psoCode, mappingByPSO.getOrDefault(psoCode, 0));
                }
                psoRows.add(row);
            }
            result.put("course_outcomes_with_co_pso_matrix", psoRows);
        }

        return ResponseEntity.ok(result);
    }

    // ─────────────────────────────────────────────
    // 5. ALL POs  GET /obe-summary/po
    // ─────────────────────────────────────────────
    @GetMapping("/po")
    public ResponseEntity<?> getAllPO() {
        List<PO> pos = poRepository.findAll();
        List<Map<String, Object>> result = pos.stream()
                .sorted(Comparator.comparing(PO::getCode))
                .map(po -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", po.getCode());
                    m.put("description", po.getDescription());
                    m.put("program", po.getProgram().getName());
                    return m;
                }).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("total", result.size(), "program_outcomes", result));
    }

    // ─────────────────────────────────────────────
    // 6. ALL PSOs  GET /obe-summary/pso
    // ─────────────────────────────────────────────
    @GetMapping("/pso")
    public ResponseEntity<?> getAllPSO() {
        List<PSO> psos = psoRepository.findAll();
        List<Map<String, Object>> result = psos.stream()
                .sorted(Comparator.comparing(PSO::getCode))
                .map(pso -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", pso.getCode());
                    m.put("description", pso.getDescription());
                    m.put("specialization", pso.getSpecialization().getName());
                    return m;
                }).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("total", result.size(), "program_specific_outcomes", result));
    }
}


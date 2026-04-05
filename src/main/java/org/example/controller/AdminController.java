package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.entity.*;
import org.example.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ProgramRepository programRepository;
    private final BatchRepository batchRepository;
    private final SemesterRepository semesterRepository;
    private final CourseRepository courseRepository;
    private final CORepository coRepository;
    private final PORepository poRepository;
    private final PSORepository psoRepository;
    private final CO_PO_MappingRepository coPoMappingRepository;
    private final COPSORepository coPsoMappingRepository;
    private final SpecializationRepository specializationRepository;

    // ═══ PROGRAMS ════════════════════════════════════════════════

    @GetMapping("/programs")
    public List<Map<String, Object>> getPrograms() {
        return programRepository.findAll().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            return m;
        }).collect(Collectors.toList());
    }

    @PostMapping("/programs")
    public ResponseEntity<?> createProgram(@RequestBody Map<String, String> body) {
        Program p = new Program();
        p.setName(body.get("name"));
        return ResponseEntity.ok(programRepository.save(p));
    }

    @PutMapping("/programs/{id}")
    public ResponseEntity<?> updateProgram(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return programRepository.findById(id).map(p -> {
            if (body.containsKey("name"))
                p.setName(body.get("name"));
            return ResponseEntity.ok(programRepository.save(p));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/programs/{id}")
    public ResponseEntity<?> deleteProgram(@PathVariable Long id) {
        if (!programRepository.existsById(id))
            return ResponseEntity.notFound().build();
        programRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ═══ BATCHES ═════════════════════════════════════════════════

    @GetMapping("/batches")
    public List<Map<String, Object>> getBatches(@RequestParam(required = false) Long programId) {
        List<Batch> batches = programId != null
                ? batchRepository.findAll().stream()
                        .filter(b -> b.getProgram() != null && b.getProgram().getId().equals(programId))
                        .collect(Collectors.toList())
                : batchRepository.findAll();
        return batches.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("startYear", b.getStartYear());
            m.put("endYear", b.getEndYear());
            m.put("programId", b.getProgram() != null ? b.getProgram().getId() : null);
            m.put("programName", b.getProgram() != null ? b.getProgram().getName() : null);
            m.put("specializationId", b.getSpecialization() != null ? b.getSpecialization().getId() : null);
            m.put("specializationName", b.getSpecialization() != null ? b.getSpecialization().getName() : null);
            m.put("label", (b.getStartYear() != null ? b.getStartYear() : "?") + "-"
                    + (b.getEndYear() != null ? b.getEndYear() : "?"));
            return m;
        }).collect(Collectors.toList());
    }

    @PostMapping("/batches")
    public ResponseEntity<?> createBatch(@RequestBody Map<String, Object> body) {
        Long programId = body.get("programId") != null ? Long.parseLong(body.get("programId").toString()) : null;
        if (programId == null)
            return ResponseEntity.badRequest().body("programId required");
        Program program = programRepository.findById(programId).orElse(null);
        if (program == null)
            return ResponseEntity.badRequest().body("Program not found");
        Batch b = new Batch();
        b.setProgram(program);
        if (body.get("startYear") != null)
            b.setStartYear(Integer.parseInt(body.get("startYear").toString()));
        if (body.get("endYear") != null)
            b.setEndYear(Integer.parseInt(body.get("endYear").toString()));
        if (body.get("specializationId") != null) {
            Long specId = Long.parseLong(body.get("specializationId").toString());
            specializationRepository.findById(specId).ifPresent(b::setSpecialization);
        }
        return ResponseEntity.ok(batchRepository.save(b));
    }

    @PutMapping("/batches/{id}")
    public ResponseEntity<?> updateBatch(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return batchRepository.findById(id).map(b -> {
            if (body.get("startYear") != null)
                b.setStartYear(Integer.parseInt(body.get("startYear").toString()));
            if (body.get("endYear") != null)
                b.setEndYear(Integer.parseInt(body.get("endYear").toString()));
            if (body.get("programId") != null)
                programRepository.findById(Long.parseLong(body.get("programId").toString())).ifPresent(b::setProgram);
            return ResponseEntity.ok(batchRepository.save(b));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/batches/{id}")
    public ResponseEntity<?> deleteBatch(@PathVariable Long id) {
        if (!batchRepository.existsById(id))
            return ResponseEntity.notFound().build();
        batchRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ═══ SEMESTERS ═══════════════════════════════════════════════

    @GetMapping("/semesters")
    public List<Map<String, Object>> getSemesters(@RequestParam(required = false) Long batchId) {
        List<Semester> sems = batchId != null
                ? semesterRepository.findAll().stream()
                        .filter(s -> s.getBatch() != null && s.getBatch().getId().equals(batchId))
                        .collect(Collectors.toList())
                : semesterRepository.findAll();
        return sems.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("number", s.getNumber());
            m.put("batchId", s.getBatch() != null ? s.getBatch().getId() : null);
            m.put("batchLabel",
                    s.getBatch() != null ? s.getBatch().getStartYear() + "-" + s.getBatch().getEndYear() : null);
            m.put("label", "Semester " + s.getNumber());
            return m;
        }).sorted(Comparator.comparingInt(x -> (int) ((Map<String, Object>) x).getOrDefault("number", 0)))
                .collect(Collectors.toList());
    }

    @PostMapping("/semesters")
    public ResponseEntity<?> createSemester(@RequestBody Map<String, Object> body) {
        Long batchId = body.get("batchId") != null ? Long.parseLong(body.get("batchId").toString()) : null;
        if (batchId == null)
            return ResponseEntity.badRequest().body("batchId required");
        Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null)
            return ResponseEntity.badRequest().body("Batch not found");
        Semester s = new Semester();
        s.setBatch(batch);
        if (body.get("number") != null)
            s.setNumber(Integer.parseInt(body.get("number").toString()));
        return ResponseEntity.ok(semesterRepository.save(s));
    }

    @PutMapping("/semesters/{id}")
    public ResponseEntity<?> updateSemester(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return semesterRepository.findById(id).map(s -> {
            if (body.get("number") != null)
                s.setNumber(Integer.parseInt(body.get("number").toString()));
            if (body.get("batchId") != null)
                batchRepository.findById(Long.parseLong(body.get("batchId").toString())).ifPresent(s::setBatch);
            return ResponseEntity.ok(semesterRepository.save(s));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/semesters/{id}")
    public ResponseEntity<?> deleteSemester(@PathVariable Long id) {
        if (!semesterRepository.existsById(id))
            return ResponseEntity.notFound().build();
        semesterRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ═══ COURSES ═════════════════════════════════════════════════

    @GetMapping("/courses")
    public List<Map<String, Object>> getCourses(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long programId) {
        List<Course> courses;
        if (semesterId != null)
            courses = courseRepository.findBySemesterId(semesterId);
        else if (programId != null)
            courses = courseRepository.findAll().stream()
                    .filter(c -> c.getProgram() != null && c.getProgram().getId().equals(programId))
                    .collect(Collectors.toList());
        else
            courses = courseRepository.findAll();
        return courses.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("courseCode", c.getCourseCode());
            m.put("courseName", c.getCourseName());
            m.put("semesterId", c.getSemester() != null ? c.getSemester().getId() : null);
            m.put("semesterNumber", c.getSemester() != null ? c.getSemester().getNumber() : null);
            m.put("programId", c.getProgram() != null ? c.getProgram().getId() : null);
            m.put("programName", c.getProgram() != null ? c.getProgram().getName() : null);
            m.put("batchId", c.getBatch() != null ? c.getBatch().getId() : null);
            m.put("specializationId", c.getSpecialization() != null ? c.getSpecialization().getId() : null);
            return m;
        }).collect(Collectors.toList());
    }

    @PostMapping("/courses")
    public ResponseEntity<?> createCourse(@RequestBody Map<String, Object> body) {
        Course c = new Course();
        c.setCourseCode(getStr(body, "courseCode"));
        c.setCourseName(getStr(body, "courseName"));
        if (body.get("semesterId") != null)
            semesterRepository.findById(toLong(body, "semesterId")).ifPresent(c::setSemester);
        if (body.get("programId") != null)
            programRepository.findById(toLong(body, "programId")).ifPresent(c::setProgram);
        if (body.get("batchId") != null)
            batchRepository.findById(toLong(body, "batchId")).ifPresent(c::setBatch);
        return ResponseEntity.ok(courseRepository.save(c));
    }

    @PutMapping("/courses/{id}")
    public ResponseEntity<?> updateCourse(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return courseRepository.findById(id).map(c -> {
            if (body.containsKey("courseCode"))
                c.setCourseCode(getStr(body, "courseCode"));
            if (body.containsKey("courseName"))
                c.setCourseName(getStr(body, "courseName"));
            if (body.get("semesterId") != null)
                semesterRepository.findById(toLong(body, "semesterId")).ifPresent(c::setSemester);
            if (body.get("programId") != null)
                programRepository.findById(toLong(body, "programId")).ifPresent(c::setProgram);
            if (body.get("batchId") != null)
                batchRepository.findById(toLong(body, "batchId")).ifPresent(c::setBatch);
            return ResponseEntity.ok(courseRepository.save(c));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/courses/{id}")
    public ResponseEntity<?> deleteCourse(@PathVariable Long id) {
        if (!courseRepository.existsById(id))
            return ResponseEntity.notFound().build();
        courseRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ═══ COs ══════════════════════════════════════════════════════

    @GetMapping("/cos")
    public List<Map<String, Object>> getCOs(@RequestParam(required = false) Long courseId) {
        List<CO> cos = courseId != null ? coRepository.findByCourseId(courseId) : coRepository.findAll();
        return cos.stream().map(co -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", co.getId());
            m.put("code", co.getCode());
            m.put("description", co.getDescription());
            m.put("courseId", co.getCourse() != null ? co.getCourse().getId() : null);
            m.put("courseCode", co.getCourse() != null ? co.getCourse().getCourseCode() : null);
            return m;
        }).collect(Collectors.toList());
    }

    @PostMapping("/cos")
    public ResponseEntity<?> createCO(@RequestBody Map<String, Object> body) {
        Long courseId = toLong(body, "courseId");
        if (courseId == null)
            return ResponseEntity.badRequest().body("courseId required");
        Course course = courseRepository.findById(courseId).orElse(null);
        if (course == null)
            return ResponseEntity.badRequest().body("Course not found");
        CO co = new CO();
        co.setCode(getStr(body, "code"));
        co.setDescription(getStr(body, "description"));
        co.setCourse(course);
        return ResponseEntity.ok(coRepository.save(co));
    }

    @PutMapping("/cos/{id}")
    public ResponseEntity<?> updateCO(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return coRepository.findById(id).map(co -> {
            if (body.containsKey("code"))
                co.setCode(getStr(body, "code"));
            if (body.containsKey("description"))
                co.setDescription(getStr(body, "description"));
            return ResponseEntity.ok(coRepository.save(co));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/cos/{id}")
    public ResponseEntity<?> deleteCO(@PathVariable Long id) {
        if (!coRepository.existsById(id))
            return ResponseEntity.notFound().build();
        coRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ═══ POs ══════════════════════════════════════════════════════

    @GetMapping("/pos")
    public List<Map<String, Object>> getPOs(@RequestParam(required = false) Long programId) {
        List<PO> pos = programId != null ? poRepository.findByProgramId(programId) : poRepository.findAll();
        return pos.stream().map(po -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", po.getId());
            m.put("code", po.getCode());
            m.put("description", po.getDescription());
            m.put("programId", po.getProgram() != null ? po.getProgram().getId() : null);
            m.put("programName", po.getProgram() != null ? po.getProgram().getName() : null);
            return m;
        }).collect(Collectors.toList());
    }

    @PostMapping("/pos")
    public ResponseEntity<?> createPO(@RequestBody Map<String, Object> body) {
        Long programId = toLong(body, "programId");
        if (programId == null)
            return ResponseEntity.badRequest().body("programId required");
        Program program = programRepository.findById(programId).orElse(null);
        if (program == null)
            return ResponseEntity.badRequest().body("Program not found");
        PO po = new PO();
        po.setCode(getStr(body, "code"));
        po.setDescription(getStr(body, "description"));
        po.setProgram(program);
        return ResponseEntity.ok(poRepository.save(po));
    }

    @PutMapping("/pos/{id}")
    public ResponseEntity<?> updatePO(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return poRepository.findById(id).map(po -> {
            if (body.containsKey("code"))
                po.setCode(getStr(body, "code"));
            if (body.containsKey("description"))
                po.setDescription(getStr(body, "description"));
            return ResponseEntity.ok(poRepository.save(po));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/pos/{id}")
    public ResponseEntity<?> deletePO(@PathVariable Long id) {
        if (!poRepository.existsById(id))
            return ResponseEntity.notFound().build();
        poRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ═══ PSOs ═════════════════════════════════════════════════════

    @GetMapping("/psos")
    public List<Map<String, Object>> getPSOs(@RequestParam(required = false) Long programId) {
        List<PSO> psos = programId != null
                ? psoRepository.findByProgram(programRepository.findById(programId).orElse(null))
                : psoRepository.findAll();
        if (psos == null)
            psos = List.of();
        return psos.stream().map(pso -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", pso.getId());
            m.put("code", pso.getCode());
            m.put("description", pso.getDescription());
            m.put("programId", pso.getProgram() != null ? pso.getProgram().getId() : null);
            m.put("specializationId", pso.getSpecialization() != null ? pso.getSpecialization().getId() : null);
            m.put("specializationName", pso.getSpecialization() != null ? pso.getSpecialization().getName() : null);
            return m;
        }).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────
    // 9. UTILITY: GENERATE DEFAULT MATRICES (Fix for empty handbooks)
    // ─────────────────────────────────────────────────────────────────
    @PostMapping("/matrices/generate-defaults")
    public ResponseEntity<?> generateDefaultMatrices() {
        int newPos = 0, newPsos = 0, newCoPos = 0, newCoPsos = 0;

        // 1. Ensure all Programs have 12 POs
        for (Program program : programRepository.findAll()) {
            List<PO> pos = poRepository.findByProgram(program);
            if (pos.size() < 12) {
                Set<String> existing = pos.stream().map(PO::getCode).collect(Collectors.toSet());
                for (int i = 1; i <= 12; i++) {
                    String code = "PO" + i;
                    if (!existing.contains(code)) {
                        PO po = new PO();
                        po.setCode(code);
                        po.setDescription("Standard " + code);
                        po.setProgram(program);
                        poRepository.save(po);
                        newPos++;
                    }
                }
            }
        }

        // 2. Ensure all Specializations have 4 PSOs
        for (Specialization spec : specializationRepository.findAll()) {
            List<PSO> psos = psoRepository.findByProgram(spec.getProgram()); // Should really be findBySpecialization,
                                                                             // using program as fallback
            if (psos.size() < 4) {
                Set<String> existing = psos.stream().map(PSO::getCode).collect(Collectors.toSet());
                for (int i = 1; i <= 4; i++) {
                    String code = "PSO" + i;
                    if (!existing.contains(code)) {
                        PSO pso = new PSO();
                        pso.setCode(code);
                        pso.setDescription("Standard " + code);
                        pso.setProgram(spec.getProgram());
                        psoRepository.save(pso);
                        newPsos++;
                    }
                }
            }
        }

        // 3. Fill missing CO-PO and CO-PSO for all COs
        for (Course course : courseRepository.findAll()) {
            List<CO> cos = coRepository.findByCourse(course);
            List<PO> pos = poRepository.findByProgram(course.getProgram());
            List<PSO> psos = psoRepository.findByProgram(course.getProgram());

            for (CO co : cos) {
                List<COPOMap> currentCopo = coPoMappingRepository.findByCoCourseId(course.getId()).stream()
                        .filter(m -> m.getCo().getId().equals(co.getId())).collect(Collectors.toList());
                if (currentCopo.isEmpty()) {
                    for (PO po : pos) {
                        COPOMap map = new COPOMap();
                        map.setCo(co);
                        map.setPo(po);
                        map.setWeight((int) (Math.random() * 3) + 1); // 1, 2 or 3
                        coPoMappingRepository.save(map);
                        newCoPos++;
                    }
                }

                List<COPSOMapping> currentCopso = coPsoMappingRepository.findByCo(co);
                if (currentCopso.isEmpty()) {
                    for (PSO pso : psos) {
                        COPSOMapping map = new COPSOMapping();
                        map.setCo(co);
                        map.setPso(pso);
                        map.setWeight((int) (Math.random() * 3) + 1); // 1, 2 or 3
                        coPsoMappingRepository.save(map);
                        newCoPsos++;
                    }
                }
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "Default matrices generated successfully.",
                "new_pos_created", newPos,
                "new_psos_created", newPsos,
                "new_copo_mappings_created", newCoPos,
                "new_copso_mappings_created", newCoPsos));
    }

    @PostMapping("/psos")
    public ResponseEntity<?> createPSO(@RequestBody Map<String, Object> body) {
        Long programId = toLong(body, "programId");
        if (programId == null)
            return ResponseEntity.badRequest().body("programId required");
        Program program = programRepository.findById(programId).orElse(null);
        if (program == null)
            return ResponseEntity.badRequest().body("Program not found");
        PSO pso = new PSO();
        pso.setCode(getStr(body, "code"));
        pso.setDescription(getStr(body, "description"));
        pso.setProgram(program);
        if (body.get("specializationId") != null)
            specializationRepository.findById(toLong(body, "specializationId")).ifPresent(pso::setSpecialization);
        return ResponseEntity.ok(psoRepository.save(pso));
    }

    @PutMapping("/psos/{id}")
    public ResponseEntity<?> updatePSO(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return psoRepository.findById(id).map(pso -> {
            if (body.containsKey("code"))
                pso.setCode(getStr(body, "code"));
            if (body.containsKey("description"))
                pso.setDescription(getStr(body, "description"));
            return ResponseEntity.ok(psoRepository.save(pso));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/psos/{id}")
    public ResponseEntity<?> deletePSO(@PathVariable Long id) {
        if (!psoRepository.existsById(id))
            return ResponseEntity.notFound().build();
        psoRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ═══ CO-PO MAPPING ════════════════════════════════════════════

    @GetMapping("/copo")
    public Map<String, Object> getCoPoMapping(@RequestParam Long courseId) {
        List<CO> cos = coRepository.findByCourseId(courseId);
        Course course = courseRepository.findById(courseId).orElse(null);
        List<PO> pos = course != null && course.getProgram() != null
                ? poRepository.findByProgramId(course.getProgram().getId())
                : List.of();
        pos.sort(Comparator.comparing(PO::getCode));

        // Build current weight map: coId -> poId -> weight
        Map<Long, Map<Long, Integer>> weights = new LinkedHashMap<>();
        for (CO co : cos) {
            Map<Long, Integer> row = new LinkedHashMap<>();
            for (PO po : pos)
                row.put(po.getId(), 0);
            weights.put(co.getId(), row);
        }
        coPoMappingRepository.findByCoCourseId(courseId).forEach(m -> {
            if (weights.containsKey(m.getCo().getId()))
                weights.get(m.getCo().getId()).put(m.getPo().getId(), m.getWeight());
        });

        return Map.of(
                "cos",
                cos.stream()
                        .map(co -> Map.of("id", co.getId(), "code", co.getCode(), "description",
                                co.getDescription() != null ? co.getDescription() : ""))
                        .collect(Collectors.toList()),
                "pos",
                pos.stream()
                        .map(po -> Map.of("id", po.getId(), "code", po.getCode(), "description",
                                po.getDescription() != null ? po.getDescription() : ""))
                        .collect(Collectors.toList()),
                "weights", weights);
    }

    /**
     * Bulk-save CO-PO mapping for a course.
     * Body: { courseId: Long, weights: { coId: { poId: weight } } }
     */
    @PutMapping("/copo")
    public ResponseEntity<?> saveCoPoMapping(@RequestBody Map<String, Object> body) {
        Long courseId = toLong(body, "courseId");
        if (courseId == null)
            return ResponseEntity.badRequest().body("courseId required");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Integer>> weights = (Map<String, Map<String, Integer>>) body.get("weights");
        if (weights == null)
            return ResponseEntity.badRequest().body("weights required");

        // Delete existing mappings for this course
        List<COPOMap> existing = coPoMappingRepository.findByCoCourseId(courseId);
        coPoMappingRepository.deleteAll(existing);

        // Save new mappings
        List<COPOMap> toSave = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> coEntry : weights.entrySet()) {
            Long coId = Long.parseLong(coEntry.getKey());
            CO co = coRepository.findById(coId).orElse(null);
            if (co == null)
                continue;
            for (Map.Entry<String, Integer> poEntry : coEntry.getValue().entrySet()) {
                int w = poEntry.getValue() == null ? 0 : poEntry.getValue();
                if (w == 0)
                    continue; // skip zero weights
                Long poId = Long.parseLong(poEntry.getKey());
                PO po = poRepository.findById(poId).orElse(null);
                if (po == null)
                    continue;
                COPOMap m = new COPOMap();
                m.setCo(co);
                m.setPo(po);
                m.setWeight(w);
                toSave.add(m);
            }
        }
        coPoMappingRepository.saveAll(toSave);
        return ResponseEntity.ok(Map.of("saved", toSave.size()));
    }

    // ═══ CO-PSO MAPPING ═══════════════════════════════════════════

    @GetMapping("/copso")
    public Map<String, Object> getCoPsoMapping(@RequestParam Long courseId) {
        List<CO> cos = coRepository.findByCourseId(courseId);
        Course course = courseRepository.findById(courseId).orElse(null);
        List<PSO> psos = List.of();
        if (course != null && course.getProgram() != null) {
            psos = psoRepository.findByProgram(course.getProgram());
            if (psos == null)
                psos = List.of();
        }
        psos.sort(Comparator.comparing(PSO::getCode));

        Map<Long, Map<Long, Integer>> weights = new LinkedHashMap<>();
        for (CO co : cos) {
            Map<Long, Integer> row = new LinkedHashMap<>();
            for (PSO pso : psos)
                row.put(pso.getId(), 0);
            weights.put(co.getId(), row);
        }
        for (CO co : cos) {
            for (COPSOMapping m : coPsoMappingRepository.findByCo(co)) {
                if (weights.containsKey(co.getId()))
                    weights.get(co.getId()).put(m.getPso().getId(), m.getWeight());
            }
        }

        List<PSO> finalPsos = psos;
        return Map.of(
                "cos",
                cos.stream()
                        .map(co -> Map.of("id", co.getId(), "code", co.getCode(), "description",
                                co.getDescription() != null ? co.getDescription() : ""))
                        .collect(Collectors.toList()),
                "psos",
                finalPsos.stream()
                        .map(pso -> Map.of("id", pso.getId(), "code", pso.getCode(), "description",
                                pso.getDescription() != null ? pso.getDescription() : ""))
                        .collect(Collectors.toList()),
                "weights", weights);
    }

    @PutMapping("/copso")
    public ResponseEntity<?> saveCoPsoMapping(@RequestBody Map<String, Object> body) {
        Long courseId = toLong(body, "courseId");
        if (courseId == null)
            return ResponseEntity.badRequest().body("courseId required");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Integer>> weights = (Map<String, Map<String, Integer>>) body.get("weights");
        if (weights == null)
            return ResponseEntity.badRequest().body("weights required");

        List<CO> cos = coRepository.findByCourseId(courseId);
        for (CO co : cos)
            coPsoMappingRepository.deleteAll(coPsoMappingRepository.findByCo(co));

        List<COPSOMapping> toSave = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> coEntry : weights.entrySet()) {
            Long coId = Long.parseLong(coEntry.getKey());
            CO co = coRepository.findById(coId).orElse(null);
            if (co == null)
                continue;
            for (Map.Entry<String, Integer> psoEntry : coEntry.getValue().entrySet()) {
                int w = psoEntry.getValue() == null ? 0 : psoEntry.getValue();
                if (w == 0)
                    continue;
                Long psoId = Long.parseLong(psoEntry.getKey());
                PSO pso = psoRepository.findById(psoId).orElse(null);
                if (pso == null)
                    continue;
                COPSOMapping m = new COPSOMapping();
                m.setCo(co);
                m.setPso(pso);
                m.setWeight(w);
                toSave.add(m);
            }
        }
        coPsoMappingRepository.saveAll(toSave);
        return ResponseEntity.ok(Map.of("saved", toSave.size()));
    }

    // ═══ HELPERS ══════════════════════════════════════════════════

    private String getStr(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v != null ? v.toString() : null;
    }

    private Long toLong(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null)
            return null;
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

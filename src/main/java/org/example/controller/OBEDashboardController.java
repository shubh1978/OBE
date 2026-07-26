package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.entity.*;
import org.example.repository.*;
import org.example.service.AttainmentService;
import org.example.util.EnrollmentCodeUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class OBEDashboardController {

    private final ProgramRepository          programRepository;
    private final BatchRepository            batchRepository;
    private final SemesterRepository         semesterRepository;
    private final CourseRepository           courseRepository;
    private final CORepository               coRepository;
    private final PORepository               poRepository;
    private final PSORepository              psoRepository;
    private final CO_PO_MappingRepository    copoRepository;
    private final COPSORepository            copsoRepository;
    private final StudentMarkRepository      studentMarkRepository;
    private final StudentRepository          studentRepository;
    private final SpecializationRepository   specializationRepository;
    private final QuestionCOMappingRepository questionCOMappingRepository;
    private final AttainmentService          attainmentService;
    private final EnrollmentCodeUtil         enrollmentCodeUtil;

    @GetMapping("/batches")
    public ResponseEntity<?> getBatches(@RequestParam(required = false) Long programId) {
        // Fetch batches; filter by program when programId is provided
        List<Batch> allBatches = (programId != null)
            ? batchRepository.findByProgramId(programId)
            : batchRepository.findAll();

        Map<Integer, Integer> startToEnd = new TreeMap<>(Comparator.reverseOrder());
        Set<Integer> realBatchYears = new TreeSet<>();
        for (Batch b : allBatches) {
            try {
                int sy = b.getStartYear();
                int ey; try { ey = b.getEndYear(); } catch (Exception e2) { ey = sy + 3; }
                if (sy > 0) {
                    startToEnd.merge(sy, ey > 0 ? ey : sy + 3, (a, bv) -> Math.max(a, bv));
                    realBatchYears.add(sy);
                }
            } catch (Exception ignored) {}
        }

        List<Map<String, Object>> batches = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : startToEnd.entrySet()) {
            if (!realBatchYears.contains(e.getKey())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey()); m.put("year", String.valueOf(e.getKey()));
            m.put("label", e.getKey() + "-" + e.getValue() + " Batch");
            batches.add(m);
        }
        batches.sort((a, b2) -> Integer.compare((int)b2.get("id"), (int)a.get("id")));
        return ResponseEntity.ok(batches);
    }


    @GetMapping("/programs")
    public ResponseEntity<?> getPrograms() {
        return ResponseEntity.ok(programRepository.findAll().stream()
                .map(p -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", p.getId()); m.put("name", p.getName()); return m; })
                .collect(Collectors.toList()));
    }

    @GetMapping("/specializations")
    public ResponseEntity<?> getSpecializations(
            @RequestParam Long programId,
            @RequestParam(required = false) String batchYear) {
        Program prog = programRepository.findById(programId).orElse(null);
        if (prog == null) return ResponseEntity.ok(List.of());

        List<Specialization> allSpecs = specializationRepository.findByProgram(prog);

        // If batchYear is given, filter to only specializations that have a batch
        // with that startYear (so "CS 2025" doesn't show under "2023 batch")
        if (batchYear != null && !batchYear.isBlank()) {
            try {
                int year = Integer.parseInt(batchYear);
                Set<Long> specIdsWithBatch = batchRepository.findByProgram(prog).stream()
                        .filter(b -> b.getStartYear() != null && b.getStartYear() == year
                                  && b.getSpecialization() != null)
                        .map(b -> b.getSpecialization().getId())
                        .collect(Collectors.toSet());
                // Also include specializations whose batch startYear is <= batchYear
                // (shared/common batches cover multiple student years)
                // Include a spec if it has ANY batch whose startYear matches OR
                // has no batch at all for this year (legacy data without batch-scoped specs)
                if (!specIdsWithBatch.isEmpty()) {
                    allSpecs = allSpecs.stream()
                            .filter(s -> specIdsWithBatch.contains(s.getId()))
                            .collect(Collectors.toList());
                }
                // If no specs match the year filter, fall back to showing all specs
                // (avoids blank dropdown when data is partially structured)
            } catch (NumberFormatException ignored) {}
        }

        return ResponseEntity.ok(allSpecs.stream()
                .map(s -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", s.getId()); m.put("name", s.getName()); return m; })
                .collect(Collectors.toList()));
    }

    @GetMapping("/semesters")
    public ResponseEntity<?> getSemesters(
            @RequestParam Long programId,
            @RequestParam(required = false) Long specializationId,
            @RequestParam(required = false) String batchYear) {
        Program prog = programRepository.findById(programId).orElse(null);
        if (prog == null) return ResponseEntity.ok(List.of());
        List<Batch> batches = batchRepository.findByProgram(prog);

        // Filter by specialization: each spec has its own batch with its own semester IDs.
        if (specializationId != null) {
            List<Batch> specBatches = batches.stream()
                    .filter(b -> b.getSpecialization() != null && specializationId.equals(b.getSpecialization().getId()))
                    .collect(Collectors.toList());
            if (!specBatches.isEmpty()) batches = specBatches;
        }

        // When batchYear is specified, prefer batches from that exact start year.
        // This ensures the correct semester IDs (e.g., BCA 2025 batch sem_id=114, not BCA 2023's sem_id=42).
        if (batchYear != null && !batchYear.isBlank() && !batchYear.equals("all")) {
            try {
                int fy = Integer.parseInt(batchYear);
                List<Batch> yearBatches = batches.stream()
                        .filter(b -> b.getStartYear() != null && b.getStartYear() == fy)
                        .collect(Collectors.toList());
                if (!yearBatches.isEmpty()) batches = yearBatches;
                // If no year-specific batch exists, fall through and use all batches
                // (preserves backward compat for BCA/MCA 2024 cohort using 2023 batch)
            } catch (NumberFormatException ignored) {}
        }

        Map<Integer, Long> semNumToId = new TreeMap<>();
        for (Batch b : batches) {
            for (Semester s : semesterRepository.findByBatch(b)) {
                try { int num = s.getNumber(); if (!semNumToId.containsKey(num)) semNumToId.put(num, s.getId()); }
                catch (Exception ignored) {}
            }
        }
        return ResponseEntity.ok(semNumToId.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getValue()); m.put("number", e.getKey()); m.put("label", "Semester " + e.getKey()); return m;
        }).collect(Collectors.toList()));
    }

    @GetMapping("/attainment")
    public ResponseEntity<?> getAttainment(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long programId,
            @RequestParam(required = false) Long specializationId,
            @RequestParam(required = false) String courseCode,
            @RequestParam(required = false) String batchYear) {

        List<Course> courses;
        if (semesterId != null) {
            if (semesterRepository.findById(semesterId).isEmpty()) return ResponseEntity.ok(emptyResult());
            // semesterId is now specialization-specific (getSemesters filters by spec first),
            // so this automatically returns only courses in the correct specialization's semester.
            courses = new ArrayList<>(courseRepository.findBySemesterId(semesterId));
        } else {
            courses = getCoursesForFilter(programId, specializationId, batchYear);
        }
        if (batchYear != null && !batchYear.isBlank() && !batchYear.equals("all")) {
            try { int fy = Integer.parseInt(batchYear);
                courses = courses.stream().filter(c -> { if (c.getBatch()==null) return true; try { return fy==c.getBatch().getStartYear(); } catch (Exception e) { return false; } }).collect(Collectors.toList());
            } catch (NumberFormatException ignored) {}
        }
        if (courseCode != null && !courseCode.isBlank())
            courses = courses.stream().filter(c -> courseCode.equalsIgnoreCase(c.getCourseCode())).collect(Collectors.toList());
        // Dedup: if structure parser still has duplicate entities for same course code, prefer the one with marks
        courses = deduplicateCourses(courses);

        List<Map<String, Object>> courseResults = new ArrayList<>();
        int totalStudents = 0, atRiskCount = 0;
        Map<String, List<Double>> branchPoAtt = new LinkedHashMap<>(), branchPsoAtt = new LinkedHashMap<>();

        // Year-prefix filtering scopes marks to the selected cohort.
        // For BTech each batch = one enrollment year (2025 batch → "25xx" students).
        // For BCA/MCA/BSc/MTech one DB batch may hold students from multiple enrollment
        // years (data inconsistency), so skip the prefix filter and use specialization_id.
        boolean isBTech = (programId != null && programRepository.findById(programId)
                .map(p -> "BTech".equalsIgnoreCase(p.getName())).orElse(false));
        String attYearPrefix = null;
        if (isBTech && batchYear != null && !batchYear.isBlank() && !batchYear.equals("all")) {
            try {
                int fy = Integer.parseInt(batchYear);
                attYearPrefix = String.format("%02d", fy % 100); // any year → 2-digit prefix
            } catch (NumberFormatException ignored) {}
        }
        final String finalAttYearPrefix = attYearPrefix;

        for (Course course : courses) {
            final Long specId = specializationId;
            List<StudentMark> marks;
            if (specId != null) {
                // Primary: filter by student.specialization_id (+ year prefix only for BTech)
                marks = new ArrayList<>(studentMarkRepository
                        .findByCourseAndSpecIdAndYear(course, specId, finalAttYearPrefix));
                // Fallback: filter by enrollment-code spec digits (catches students without spec ID set)
                if (marks.isEmpty()) {
                    List<String> specCodes = enrollmentCodeUtil.getEnrollmentCodesForSpecId(specId);
                    if (!specCodes.isEmpty()) {
                        marks = new ArrayList<>(studentMarkRepository
                                .findByCourseAndEnrollmentSpecCodesAndYear(course, specCodes, finalAttYearPrefix));
                    }
                }
                // Last resort: all marks for the course (course is already batch+spec scoped)
                if (marks.isEmpty()) {
                    marks = studentMarkRepository.findByCourse(course);
                }
            } else {
                marks = studentMarkRepository.findByCourse(course);
            }

            // Skip courses with no marks for this filter combination
            if (marks.isEmpty()) continue;

            List<CO> cos = new ArrayList<>(coRepository.findByCourse(course));

            // Sort COs numerically
            cos.sort(Comparator.comparingInt(co -> { try { return Integer.parseInt(extractCoNum(co.getCode())); } catch (Exception e) { return 999; } }));

            // Synthesize COs from question patterns only if NO COs in DB and marks exist
            if (cos.isEmpty() && !marks.isEmpty()) {
                marks.stream().map(StudentMark::getQuestion)
                        .filter(q -> q != null && q.matches("Q\\d+.*"))
                        .map(q -> q.replaceAll("^Q0*(\\d+).*", "$1"))
                        .filter(n -> n.matches("\\d+"))
                        .distinct()
                        .sorted(Comparator.comparingInt(Integer::parseInt))
                        .forEach(num -> {
                            CO co = new CO(); co.setCode(course.getCourseCode() + "-CO" + num);
                            co.setDescription("Course Outcome " + num); co.setCourse(course); cos.add(co);
                        });
            }

            Map<Long, List<StudentMark>> byStudent = marks.stream()
                    .collect(Collectors.groupingBy(sm -> sm.getStudent().getId()));
            totalStudents = Math.max(totalStudents, byStudent.size());

            // ── CO Attainment — scoped to selected specialization ────────────────────
            Map<String, Double> coAttMap = attainmentService.calculateCOAttainment(course.getId(), specId);

            List<Map<String, Object>> coAttainments = new ArrayList<>();
            for (Map.Entry<String, Double> entry : coAttMap.entrySet()) {
                String coCode = entry.getKey();
                double pct = r1(entry.getValue());
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("co", coCode);
                cm.put("description", "Course Outcome " + extractCoNum(coCode));
                cm.put("attainment", pct); cm.put("target", 60.0);
                coAttainments.add(cm);
            }
            double courseAvg = coAttMap.isEmpty() ? 0 : r1(coAttMap.values().stream().mapToDouble(d->d).average().orElse(0));

            // ── At-risk count ────────────────────────────────────────────
            for (List<StudentMark> sms : byStudent.values()) {
                double got = sms.stream().filter(sm -> sm.getMaxMarks()!=null&&sm.getMaxMarks()>0).mapToDouble(sm -> sm.getMarks()!=null?sm.getMarks():0).sum();
                double max = sms.stream().filter(sm -> sm.getMaxMarks()!=null&&sm.getMaxMarks()>0).mapToDouble(StudentMark::getMaxMarks).sum();
                if (max > 0 && (got/max) < 0.40) atRiskCount++;
            }

            // ── PO Attainment ────────────────────────────────────────────
            Program prog = course.getProgram();
            List<PO> pos = prog != null ? new ArrayList<>(poRepository.findByProgram(prog)) : new ArrayList<>();
            pos.sort(Comparator.comparingInt(po -> { try { return Integer.parseInt(po.getCode().replaceAll("\\D+","")); } catch (Exception e) { return 999; } }));
            List<String> poHeaders = pos.stream().map(PO::getCode).collect(Collectors.toList());

            Map<String, Double> poAttService = attainmentService.calculatePOAttainment(course.getId(), specId);

            Map<String, Double> poAttainment = new LinkedHashMap<>();
            for (String p : poHeaders) {
                double score0to3 = poAttService.getOrDefault(p, 0.0);
                double att = r1((score0to3 / 3.0) * 100.0);
                poAttainment.put(p, att); branchPoAtt.computeIfAbsent(p, k -> new ArrayList<>()).add(att);
            }

            // ── PSO Attainment ───────────────────────────────────────────
            List<String> psoHeaders = new ArrayList<>();
            Map<String, Double> psoAttainment = new LinkedHashMap<>();
            if (prog != null) {
                List<PSO> psos = new ArrayList<>(psoRepository.findByProgram(prog));
                psos.removeIf(p -> p.getCode() == null || p.getCode().toUpperCase().contains("CODE"));
                psos.sort(Comparator.comparingInt(pso -> { try { return Integer.parseInt(pso.getCode().replaceAll("\\D+","")); } catch (Exception e) { return 999; } }));
                psoHeaders = psos.stream().map(PSO::getCode).collect(Collectors.toList());

                if (!psoHeaders.isEmpty()) {
                    Map<String, Double> psoAttService = attainmentService.calculatePSOAttainment(course.getId(), specId);

                    for (String ps : psoHeaders) {
                        double score0to3 = psoAttService.getOrDefault(ps, 0.0);
                        double att = r1((score0to3 / 3.0) * 100.0);
                        psoAttainment.put(ps, att); branchPsoAtt.computeIfAbsent(ps, k -> new ArrayList<>()).add(att);
                    }
                }
            }

            // ── Combined CO-PO-PSO Mapping Matrix ────────────────────────
            List<Map<String, Object>> coMappingMatrix = new ArrayList<>();
            for (CO co : cos) {
                Map<String, Object> row = new LinkedHashMap<>(); 
                row.put("co", co.getCode());
                Map<String, Integer> poWeights = new HashMap<>();
                Map<String, Integer> psoWeights = new HashMap<>();
                if (co.getId() != null) {
                    copoRepository.findByCoId(co.getId()).forEach(m -> poWeights.put(m.getPo().getCode(), m.getWeight()));
                    copsoRepository.findByCoId(co.getId()).forEach(m -> psoWeights.put(m.getPso().getCode(), m.getWeight()));
                }
                for (String p : poHeaders) {
                    row.put(p, poWeights.getOrDefault(p, 0));
                }
                for (String ps : psoHeaders) {
                    row.put(ps, psoWeights.getOrDefault(ps, 0));
                }
                coMappingMatrix.add(row);
            }

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id", course.getId()); res.put("courseCode", course.getCourseCode()); res.put("courseName", course.getCourseName());
            res.put("studentCount", byStudent.size()); res.put("avgAttainment", courseAvg);
            res.put("coAttainments", coAttainments); res.put("poHeaders", poHeaders);
            res.put("psoHeaders", psoHeaders);
            res.put("coMappingMatrix", coMappingMatrix); 
            res.put("poAttainment", poAttainment);
            res.put("psoAttainment", psoAttainment);
            courseResults.add(res);
        }

        Map<String, Double> branchPoAvg = new LinkedHashMap<>(), branchPsoAvg = new LinkedHashMap<>();
        branchPoAtt.forEach((k,v) -> branchPoAvg.put(k, r1(v.stream().mapToDouble(d->d).average().orElse(0))));
        branchPsoAtt.forEach((k,v) -> branchPsoAvg.put(k, r1(v.stream().mapToDouble(d->d).average().orElse(0))));
        double overallAtt = courseResults.isEmpty() ? 0 : r1(courseResults.stream()
                .filter(c -> (double)c.get("avgAttainment") > 0)
                .mapToDouble(c -> (double)c.get("avgAttainment")).average().orElse(0));

        Map<String, Object> resp = new LinkedHashMap<>();
         // Count all courses (including those without marks) for display in frontend
         resp.put("totalCourses", courseResults.size());
         resp.put("totalCoursesWithMarks", courseResults.stream().filter(c -> (int)c.get("studentCount") > 0).count());
         resp.put("totalStudents", totalStudents);
         resp.put("atRiskStudents", atRiskCount); resp.put("overallAttainment", overallAtt);
         resp.put("branchPoAttainment", branchPoAvg); resp.put("branchPsoAttainment", branchPsoAvg);
         resp.put("courses", courseResults);
         return ResponseEntity.ok(resp);
    }

    @GetMapping("/courses")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getCoursesDropdown(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long specializationId,
            @RequestParam(required = false) Long programId,
            @RequestParam(required = false) String batchYear,
            @RequestParam(required = false, defaultValue = "false") boolean includeEmpty) {
        List<Course> courses;
        if (semesterId != null && programId != null) {
            // Both program and semester specified — use combined filter to avoid cross-program course leakage
            courses = new ArrayList<>(courseRepository.findByProgramIdAndSemesterId(programId, semesterId));
        } else if (semesterId != null) {
            courses = new ArrayList<>(courseRepository.findBySemesterId(semesterId));
        } else {
            courses = getCoursesForFilter(programId, specializationId, batchYear);
        }

        // NOTE: We do NOT filter courses by batchYear here.
        // All BCA/MCA/BSc courses belong to a single 2023 DB batch entity.
        // Filtering by c.getBatch().getStartYear() would eliminate all courses
        // when batchYear=2024 is selected. The batchYear only filters student marks.
        courses = deduplicateCourses(courses);
        // Pre-fetch programme entity once for null-spec fallback
        final Long specId = specializationId;
        final org.example.entity.Program progEntity = (programId != null)
                ? programRepository.findById(programId).orElse(null) : null;
        // Compute year prefix for batch-aware null-spec count
        String courseListYearPrefix = null;
        if (batchYear != null && !batchYear.isBlank() && !batchYear.equals("all")) {
            try {
                int fy = Integer.parseInt(batchYear);
                courseListYearPrefix = String.format("%02d", fy % 100);
            } catch (NumberFormatException ignored) {}
        }
        final String finalCourseListYearPrefix = courseListYearPrefix;
        // Only use null-spec fallback when programme has exactly 1 spec.
        // Multi-spec programmes (BSc, BTech) can't reliably attribute null-spec students
        // to any one spec, so the fallback would bleed students across specs.
        final boolean allowNullSpecCountFallback = (progEntity != null)
                && specializationRepository.findByProgram(progEntity).size() == 1;
        final boolean finalIncludeEmpty = includeEmpty;

        return ResponseEntity.ok(courses.stream()
                .map(c -> {
                    long studentCount;
                    try {
                        if (specId != null) {
                            // Use year-aware count so this matches student performance exactly
                            studentCount = studentMarkRepository
                                    .countDistinctStudentsByCourseAndSpecializationAndYear(
                                            c, specId, finalCourseListYearPrefix);
                            // Fallback: enrollment spec codes (also year-aware)
                            if (studentCount == 0) {
                                List<String> specCodes = enrollmentCodeUtil.getEnrollmentCodesForSpecId(specId);
                                if (!specCodes.isEmpty()) {
                                    studentCount = studentMarkRepository
                                            .countDistinctStudentsByCourseAndSpecCodesAndYear(
                                                    c, specCodes, finalCourseListYearPrefix);
                                }
                            }
                            // Fallback: null-spec students (single-spec programmes only, year-aware)
                            if (studentCount == 0 && allowNullSpecCountFallback) {
                                studentCount = studentMarkRepository
                                        .countDistinctStudentsByCourseAndNullSpecAndProgram(
                                                c, progEntity, finalCourseListYearPrefix);
                            }
                        } else {
                            studentCount = studentMarkRepository.countDistinctStudentsByCourse(c);
                        }
                    } catch (Exception e) {
                        studentCount = studentMarkRepository.countDistinctStudentsByCourse(c);
                    }
                    // Exclude courses with no marks UNLESS includeEmpty=true (used by CO-PO mapping tab)
                    if (studentCount == 0 && !finalIncludeEmpty) return null;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("code", c.getCourseCode());
                    m.put("name", c.getCourseName());
                    m.put("studentCount", (int) studentCount);
                    return m;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }


    @GetMapping("/students")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getStudentPerformance(
            @RequestParam(required = false) String courseCode,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String batchYear,
            @RequestParam(required = false) Long specializationId) {
        try {
            // 1. Resolve course
            Course course;
            if (courseId != null) {
                course = courseRepository.findById(courseId).orElse(null);
            } else if (courseCode != null) {
                List<Course> candidates = courseRepository.findAllByCourseCode(courseCode);
                course = candidates.stream()
                        .max(Comparator.comparingLong(c -> studentMarkRepository.countMarksByCourse(c)))
                        .orElse(null);
            } else {
                return ResponseEntity.ok(Map.of("error", "Provide courseId or courseCode"));
            }
            if (course == null) return ResponseEntity.ok(Map.of("error", "Course not found"));

            // 2. Resolve the effective course entity — the one that actually has marks.
            //    If the selected entity has 0 marks, find the sibling (same code+program).
            Course effectiveCourse = course;
            if (course.getProgram() != null) {
                long markCount = studentMarkRepository.countMarksByCourse(course);
                if (markCount == 0) {
                    List<Course> siblings = courseRepository.findByProgram(course.getProgram());
                    effectiveCourse = siblings.stream()
                        .filter(s -> course.getCourseCode().equals(s.getCourseCode()) && !s.getId().equals(course.getId()))
                        .max(Comparator.comparingLong(s -> studentMarkRepository.countMarksByCourse(s)))
                        .filter(s -> studentMarkRepository.countMarksByCourse(s) > 0)
                        .orElse(course);
                }
            }
            final Course finalCourse = effectiveCourse;

            // 3. Load marks filtered by specialization.
            //    Primary filter: student.specialization_id (set correctly after uploading the
            //    enrollment Excel via Upload Data → Assign Specializations).
            //    When specializationId is null (no filter), all marks for the course are loaded.
            //    When a specialization is selected but returns 0 students, we return empty —
            //    this is correct and will resolve itself once enrollment data is uploaded.
            List<StudentMark> marks;
            // Only apply enrollment-year prefix filter for BTech.
            // BCA/MCA/BSc/Mtech batches can contain students from multiple enrollment years.
            boolean isBTechCourse = (finalCourse.getProgram() != null
                    && "BTech".equalsIgnoreCase(finalCourse.getProgram().getName()));
            String yearPrefix = null;
            if (isBTechCourse && batchYear != null && !batchYear.isBlank() && !batchYear.equals("all")) {
                try {
                    int fy = Integer.parseInt(batchYear);
                    yearPrefix = String.format("%02d", fy % 100); // 2025 → "25"
                } catch (NumberFormatException ignored) {}
            }
            final String finalYearPrefix = yearPrefix;

            boolean usedNullSpecFallback = false;
            if (specializationId != null) {
                marks = new ArrayList<>(studentMarkRepository.findByCourseAndSpecId(finalCourse, specializationId));
                // Fallback #1: use enrollment spec codes when specialization_id is not assigned
                if (marks.isEmpty()) {
                    List<String> specCodes = enrollmentCodeUtil.getEnrollmentCodesForSpecId(specializationId);
                    if (!specCodes.isEmpty()) {
                        marks = new ArrayList<>(studentMarkRepository.findByCourseAndEnrollmentSpecCodes(finalCourse, specCodes));
                    }
                }
                // Fallback #2: if the strict spec filter returns 0, include null-spec students
                // from the same programme and batch year (MCA, BCA, BSc without enrollment Excel).
                // Pass finalYearPrefix so the DB pre-filters to the correct enrollment year.
                if (marks.isEmpty()) {
                    org.example.entity.Program fallbackProg = null;
                    if (finalCourse.getProgram() != null) {
                        fallbackProg = finalCourse.getProgram();
                    } else {
                        org.example.entity.Specialization sp = specializationRepository.findById(specializationId).orElse(null);
                        if (sp != null) fallbackProg = sp.getProgram();
                    }
                    // Only use null-spec fallback for single-spec programmes (MCA, BCA).
                    // Multi-spec programmes (BSc, BTech) have multiple specs per programme;
                    // null-spec students cannot be attributed to any one spec, so using them
                    // would bleed Data Science students into Cyber Security views etc.
                    boolean singleSpec = (fallbackProg != null)
                            && specializationRepository.findByProgram(fallbackProg).size() == 1;
                    if (fallbackProg != null && singleSpec) {
                        marks = new ArrayList<>(studentMarkRepository
                                .findByCourseAndProgramWithNullSpec(finalCourse, fallbackProg, finalYearPrefix));
                        usedNullSpecFallback = !marks.isEmpty();
                    }
                }
            } else {
                marks = new ArrayList<>(studentMarkRepository.findByCourseWithStudent(finalCourse));
            }

            // Fallback #3 (cross-instance): if still empty and the course has a code,
            // search marks across ALL course instances with the same code, filtered by spec.
            // This handles the case where ingestion stored marks under course A even though
            // the student belongs to course B (same code, different specialization instance).
            if (marks.isEmpty() && finalCourse.getCourseCode() != null && !finalCourse.getCourseCode().isBlank()) {
                if (specializationId != null) {
                    marks = new ArrayList<>(studentMarkRepository.findByCourseCodeAndSpecId(
                            finalCourse.getCourseCode(), specializationId));
                } else {
                    marks = new ArrayList<>(studentMarkRepository.findByCourseCode(
                            finalCourse.getCourseCode()));
                }
            }

            // Filter by enrollment year prefix — only for BTech (where batch year == enrollment year).
            if (finalYearPrefix != null) {
                marks = marks.stream().filter(sm -> {
                    if (sm.getStudent() == null) return false;
                    String enr = sm.getStudent().getEnrollmentNumber();
                    // KR-prefix is a special enrollment format — always include
                    if (enr != null && enr.startsWith("KR")) return true;
                    return enr != null && enr.length() >= 2 && enr.startsWith(finalYearPrefix);
                }).collect(Collectors.toList());
            }

            // 5. Prepare CO list (sorted) — use finalCourse (the entity that has marks + COs)
            List<CO> coList = new ArrayList<>(coRepository.findByCourseId(finalCourse.getId()));
            // If the effective course has no COs, fall back to the original selected course
            if (coList.isEmpty()) coList = new ArrayList<>(coRepository.findByCourseId(course.getId()));
            coList.sort(Comparator.comparingInt(co -> { try { return Integer.parseInt(extractCoNum(co.getCode())); } catch (Exception e) { return 999; } }));

            // ── Deduplicate COs: prefer full-code (COURSECODE-CO1) over short-code (CO1) ──
            // The DB may contain both if the structure parser and ZipIngestionService both ran.
            // Key = numeric CO number extracted from code (e.g. "1" for both "CO1" and "ENMA102-CO1");
            // longer code (full-code) wins.
            {
                java.util.Map<String, CO> bestByNum = new java.util.LinkedHashMap<>();
                for (CO co : coList) {
                    String num = extractCoNum(co.getCode());
                    CO existing = bestByNum.get(num);
                    if (existing == null || co.getCode().length() > existing.getCode().length()) {
                        bestByNum.put(num, co);
                    }
                }
                coList = new ArrayList<>(bestByNum.values());
            }

            if (marks.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "courseCode", finalCourse.getCourseCode() != null ? finalCourse.getCourseCode() : "",
                    "courseName", finalCourse.getCourseName() != null ? finalCourse.getCourseName() : "",
                    "students", List.of(), "coCodes", List.of(), "poHeaders", List.of()));
            }

            List<String> coCodes;
            if (!coList.isEmpty()) {
                coCodes = coList.stream().map(CO::getCode).collect(Collectors.toList());
            } else {
                // No COs defined — derive from questions in the mark data
                List<CO> synth = new ArrayList<>();
                marks.stream().map(StudentMark::getQuestion)
                    .filter(q -> q != null && q.matches("Q\\d+.*"))
                    .map(q -> q.replaceAll("^Q0*(\\d+).*", "$1")).filter(n -> n.matches("\\d+"))
                    .distinct().sorted(Comparator.comparingInt(Integer::parseInt))
                    .forEach(num -> {
                        CO co = new CO(); co.setCode(course.getCourseCode() + "-CO" + num);
                        co.setDescription("Course Outcome " + num); co.setCourse(course); synth.add(co);
                    });
                coList = synth;
                coCodes = coList.stream().map(CO::getCode).collect(Collectors.toList());
            }

            // 6. Pre-load QCO mappings: fall back to any mapping for same course_code if course has none.
            //    Use CO CODES (not CO IDs) to avoid cross-instance ID mismatches when the same
            //    course exists for multiple specializations (e.g. 6 ENCS301 instances).
            List<QuestionCOMapping> qcoMappings = questionCOMappingRepository.findByCourseId(course.getId());
            // Always also check by course_code to get the MOST COMPLETE mapping set.
            // A specific course instance may have partial mappings (e.g. only Q1→CO1)
            // while another instance of the same course has 7 full mappings.
            // We pick whichever set is larger.
            if (course.getCourseCode() != null && !course.getCourseCode().isBlank()) {
                List<QuestionCOMapping> allMappings = questionCOMappingRepository.findByCourseCode(course.getCourseCode());
                if (allMappings.size() > qcoMappings.size()) {
                    qcoMappings = allMappings;
                }
            }

            // questionToCoCode: UPPER(questionLabel) → list of normalized CO codes for THIS course
            // e.g. "Q2" → ["ENCS301-CO3"]  (could map to multiple COs in edge cases)
            Map<String, List<String>> questionToCoCode = new HashMap<>();
            // coCodeMaxMarks: coCode → total max marks per CO
            Map<String, Double> coCodeMaxMarks = new HashMap<>();

            for (QuestionCOMapping qm : qcoMappings) {
                if (qm.getQuestionLabel() == null || qm.getCo() == null || qm.getCo().getCode() == null) continue;
                // Normalize CO code to the current course's code
                // Mapping CO code may be "ENCS301-CO2" or just "CO2"; normalize to "COURSECODE-CO#"
                String rawCode = qm.getCo().getCode();
                String coNum = rawCode.replaceAll("^.*?-?(CO\\d+)$", "$1"); // extract "CO2"
                String normalizedCoCode = course.getCourseCode() + "-" + coNum; // "ENCS301-CO2"
                String key = qm.getQuestionLabel().toUpperCase();
                questionToCoCode.computeIfAbsent(key, k -> new ArrayList<>()).add(normalizedCoCode);
                coCodeMaxMarks.merge(normalizedCoCode, qm.getMaxMarks(), Double::sum);
            }
            // Deduplicate: if a question maps to the same CO multiple times (different max marks),
            // keep each CO once with the maximum max_marks (avoids double-counting)
            questionToCoCode.replaceAll((q, list) -> list.stream().distinct().collect(Collectors.toList()));
            // If Q1 maps to both CO1 AND CO2 (data inconsistency), keep both — both COs get Q1 marks.
            // coCodeMaxMarks already has each CO's total max accumulated.

            boolean hasCOMappings = !questionToCoCode.isEmpty();

            // 7. Load POs + CO-PO weights
            Program prog = course.getProgram();
            List<PO> pos = prog != null ? new ArrayList<>(poRepository.findByProgram(prog)) : List.of();
            pos.sort(Comparator.comparingInt(po -> { try { return Integer.parseInt(po.getCode().replaceAll("\\D+","")); } catch (Exception e) { return 999; } }));
            Map<String, Map<String, Integer>> coPOW = new LinkedHashMap<>();
            for (CO co : coList) {
                Map<String, Integer> w = new HashMap<>();
                if (co.getId() != null)
                    copoRepository.findByCoId(co.getId()).forEach(m -> { if (m.getPo() != null) w.put(m.getPo().getCode(), m.getWeight()); });
                coPOW.put(co.getCode(), w);
            }

            // 8. Group marks by student and compute per-student CO attainment INLINE
            Map<Long, List<StudentMark>> stMarksMap = marks.stream()
                    .collect(Collectors.groupingBy(sm -> sm.getStudent().getId()));

            List<Map<String, Object>> students = new ArrayList<>();
            for (Map.Entry<Long, List<StudentMark>> entry : stMarksMap.entrySet()) {
                Student stu = entry.getValue().get(0).getStudent();
                List<StudentMark> stMarks = entry.getValue();

                double totalObt = stMarks.stream().filter(sm -> sm.getMaxMarks() != null && sm.getMaxMarks() > 0)
                        .mapToDouble(sm -> sm.getMarks() != null ? sm.getMarks() : 0).sum();
                double totalMax = stMarks.stream().filter(sm -> sm.getMaxMarks() != null && sm.getMaxMarks() > 0)
                        .mapToDouble(StudentMark::getMaxMarks).sum();

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("enrollmentNo", stu.getEnrollmentNumber());
                row.put("name", stu.getName());

                Map<String, Double> coAtt = new LinkedHashMap<>();
                if (hasCOMappings) {
                    // Accumulate marks per CO code
                    Map<String, Double> coObt = new HashMap<>();
                    for (StudentMark sm : stMarks) {
                        if (sm.getQuestion() == null) continue;
                        List<String> targets = questionToCoCode.get(sm.getQuestion().toUpperCase());
                        if (targets == null) continue;
                        double gained = sm.getMarks() != null ? sm.getMarks() : 0.0;
                        for (String targetCode : targets) {
                            coObt.merge(targetCode, gained, Double::sum);
                        }
                    }
                    for (String coCode : coCodes) {
                        double obt = coObt.getOrDefault(coCode, 0.0);
                        double max = coCodeMaxMarks.getOrDefault(coCode, 1.0);
                        double pct = max > 0 ? r1(Math.min(100.0, obt / max * 100.0)) : 0.0;
                        row.put(coCode, pct); coAtt.put(coCode, pct);
                    }
                } else {
                    double fallbackPct = totalMax > 0 ? r1(Math.min(100.0, totalObt / totalMax * 100.0)) : 0.0;
                    for (String c : coCodes) { row.put(c, fallbackPct); coAtt.put(c, fallbackPct); }
                }

                students.add(row);
            }
            students.sort(Comparator.comparing(s -> String.valueOf(s.get("enrollmentNo"))));

            return ResponseEntity.ok(Map.of(
                "courseCode",  course.getCourseCode() != null ? course.getCourseCode() : "",
                "courseName",  course.getCourseName() != null ? course.getCourseName() : "",
                "coCodes",     coCodes,
                "poHeaders",   pos.stream().map(PO::getCode).collect(Collectors.toList()),
                "students",    students));

        } catch (Exception ex) {
            // Return a 200 with error info rather than crashing the server
            ex.printStackTrace();
            return ResponseEntity.ok(Map.of(
                "error", "Failed to load student data: " + ex.getMessage(),
                "students", List.of(), "coCodes", List.of(), "poHeaders", List.of()));
        }
    }

    @GetMapping("/verify")
    public ResponseEntity<?> verifyStructure() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("programmes",programRepository.count()); stats.put("specializations",specializationRepository.count());
        stats.put("batches",batchRepository.count()); stats.put("semesters",semesterRepository.count());
        stats.put("courses",courseRepository.count()); stats.put("cos",coRepository.count());
        stats.put("pos",poRepository.count()); stats.put("psos",psoRepository.count());
        stats.put("copo_mappings",copoRepository.count()); stats.put("copso_mappings",copsoRepository.count());
        stats.put("students",studentRepository.count()); stats.put("marks",studentMarkRepository.count());
        result.put("stats", stats);
        List<Map<String, Object>> progs = new ArrayList<>();
        for (Batch b : batchRepository.findAll()) {
            List<Course> courses = courseRepository.findByBatch(b);
            long withMarks = courses.stream().filter(cc -> !studentMarkRepository.findByCourse(cc).isEmpty()).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("programme", b.getProgram()!=null?b.getProgram().getName():"?");
            m.put("specialization", b.getSpecialization()!=null?b.getSpecialization().getName():null);
            try { m.put("startYear",b.getStartYear()); m.put("endYear",b.getEndYear()); } catch (Exception ignored) { m.put("startYear","?"); m.put("endYear","?"); }
            m.put("semesters",semesterRepository.findByBatch(b).size()); m.put("courses",courses.size()); m.put("coursesWithMarks",withMarks);
            progs.add(m);
        }
        result.put("programmes", progs);
        List<Map<String, Object>> courseList = new ArrayList<>();
        for (Course course : courseRepository.findAll()) {
            List<CO> cos = coRepository.findByCourse(course);
            long copoCount=cos.stream().mapToLong(co->copoRepository.findByCoId(co.getId()).size()).sum();
            long copsoCount=cos.stream().mapToLong(co->copsoRepository.findByCoId(co.getId()).size()).sum();
            List<StudentMark> marks = studentMarkRepository.findByCourse(course);
            long students = marks.stream().map(sm->sm.getStudent().getId()).distinct().count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("courseCode",course.getCourseCode()); m.put("courseName",course.getCourseName());
            m.put("semester",course.getSemester()!=null?course.getSemester().getNumber():null);
            m.put("cos",cos.size()); m.put("copoMappings",copoCount); m.put("copsoMappings",copsoCount);
            m.put("marks",marks.size()); m.put("students",students);
            courseList.add(m);
        }
        courseList.sort(Comparator.comparing(mm->String.valueOf(mm.get("courseCode"))));
        result.put("courses", courseList);
        result.put("pos", poRepository.findAll().stream()
                .sorted(Comparator.comparingInt(po -> { try { return Integer.parseInt(po.getCode().replaceAll("\\D+","")); } catch(Exception e){return 999;} }))
                .map(po -> { Map<String,Object> m=new LinkedHashMap<>(); m.put("code",po.getCode()); m.put("description",po.getDescription()); return m; }).collect(Collectors.toList()));
        result.put("psos", psoRepository.findAll().stream()
                .sorted(Comparator.comparingInt(pso -> { try { return Integer.parseInt(pso.getCode().replaceAll("\\D+","")); } catch(Exception e){return 999;} }))
                .map(pso -> { Map<String,Object> m=new LinkedHashMap<>(); m.put("code",pso.getCode()); m.put("description",pso.getDescription()); return m; }).collect(Collectors.toList()));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/debug")
    public ResponseEntity<?> debug(@RequestParam(required = false) Long programId) {
        List<Course> courses = programId!=null ? programRepository.findById(programId).map(courseRepository::findByProgram).orElse(List.of()) : courseRepository.findAll();
        return ResponseEntity.ok(courses.stream().map(c -> {
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("code",c.getCourseCode()); m.put("sem",c.getSemester()!=null?c.getSemester().getNumber():null);
            m.put("batch_sy",c.getBatch()!=null?c.getBatch().getStartYear():null);
            m.put("prog",c.getProgram()!=null?c.getProgram().getName():null);
            m.put("marks",studentMarkRepository.findByCourse(c).size());
            m.put("cos",coRepository.findByCourse(c).size()); return m;
        }).collect(Collectors.toList()));
    }

    private List<Course> getCoursesForFilter(Long programId, Long specializationId, String batchYear) {
        if (programId==null) return List.of();
        Program prog = programRepository.findById(programId).orElse(null);
        if (prog==null) return List.of();
        List<Course> courses = new ArrayList<>(courseRepository.findByProgram(prog));
        if (specializationId!=null)
            // Include BOTH spec-specific courses AND null-spec (shared/core) courses.
            // Null-spec courses are shared across all specializations and often contain
            // the actual mark data — excluding them causes "0 students" for all spec views.
            courses = courses.stream()
                .filter(c -> c.getSpecialization() == null
                          || specializationId.equals(c.getSpecialization().getId()))
                .collect(Collectors.toList());
        if (batchYear!=null && !batchYear.isBlank() && !batchYear.equals("all"))
            try { int fy=Integer.parseInt(batchYear);
                courses=courses.stream().filter(c -> {
                    if (c.getBatch()==null) return true;  // null-batch = shared/generic, always include
                    try { return fy==c.getBatch().getStartYear(); } catch(Exception e){return false;}
                }).collect(Collectors.toList());
            } catch (NumberFormatException ignored) {}
        return courses;
    }

    /**
     * Deduplicates courses by code, picking the entity with the most marks.
     * Uses a single bulk COUNT query instead of N individual queries → fast.
     * Program-scoped fallback finds sibling entities with marks if the filtered pool is empty.
     */
    private List<Course> deduplicateCourses(List<Course> courses) {
        if (courses.isEmpty()) return List.of();

        // Load ALL mark counts in ONE query: courseId → markCount
        Map<Long, Long> markCounts = new HashMap<>();
        for (Object[] row : studentMarkRepository.countMarksByCourseIdBulk()) {
            markCounts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }

        Map<String, Course> best = new LinkedHashMap<>();
        Map<String, Long> bestCount = new HashMap<>();
        for (Course c : courses) {
            String code = c.getCourseCode();
            if (code == null) continue;
            long cnt = c.getId() != null ? markCounts.getOrDefault(c.getId(), 0L) : 0L;
            if (!best.containsKey(code) || cnt > bestCount.getOrDefault(code, 0L)) {
                best.put(code, c);
                bestCount.put(code, cnt);
            }
        }
        // Program-scoped fallback: for codes still at 0, find any sibling entity with marks
        Program prog = courses.get(0).getProgram();
        if (prog != null) {
            List<Course> all = courseRepository.findByProgram(prog);
            for (Map.Entry<String, Long> e : bestCount.entrySet()) {
                if (e.getValue() == 0) {
                    String code = e.getKey();
                    all.stream()
                        .filter(c -> code.equals(c.getCourseCode()))
                        .max(Comparator.comparingLong(c ->
                            c.getId() != null ? markCounts.getOrDefault(c.getId(), 0L) : 0L))
                        .ifPresent(sibling -> {
                            long cnt = sibling.getId() != null ? markCounts.getOrDefault(sibling.getId(), 0L) : 0L;
                            if (cnt > 0) { best.put(code, sibling); bestCount.put(code, cnt); }
                        });
                }
            }
        }
        return new ArrayList<>(best.values());
    }

    private String extractCoNum(String code) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("CO(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(code);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("(\\d+)$").matcher(code);
        return m.find() ? m.group(1) : "1";
    }

    private double r1(double v) { return Math.round(v*10.0)/10.0; }
    private Map<String,Object> emptyResult() {
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("totalCourses",0); r.put("totalStudents",0); r.put("atRiskStudents",0); r.put("overallAttainment",0.0); r.put("courses",List.of()); return r;
    }
}
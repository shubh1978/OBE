package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.entity.*;
import org.example.repository.*;
import org.example.service.AttainmentService;
import org.springframework.http.ResponseEntity;
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
    private final AttainmentService          attainmentService;

    @GetMapping("/batches")
    public ResponseEntity<?> getBatches(@RequestParam(required = false) Long programId) {
        Map<Integer, Integer> startToEnd = new TreeMap<>(Comparator.reverseOrder());
        for (Batch b : batchRepository.findAll()) {
            try {
                int sy = b.getStartYear();
                int ey; try { ey = b.getEndYear(); } catch (Exception e2) { ey = sy + 3; }
                if (sy > 0) startToEnd.merge(sy, ey > 0 ? ey : sy + 3, (a, bv) -> Math.max(a, bv));
            } catch (Exception ignored) {}
        }
        List<Map<String, Object>> batches = new ArrayList<>();
        int i = 1;
        for (Map.Entry<Integer, Integer> e : startToEnd.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i++); m.put("year", String.valueOf(e.getKey()));
            m.put("label", e.getKey() + "-" + e.getValue() + " Batch");
            batches.add(m);
        }
        return ResponseEntity.ok(batches);
    }

    @GetMapping("/programs")
    public ResponseEntity<?> getPrograms() {
        return ResponseEntity.ok(programRepository.findAll().stream()
                .map(p -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", p.getId()); m.put("name", p.getName()); return m; })
                .collect(Collectors.toList()));
    }

    @GetMapping("/specializations")
    public ResponseEntity<?> getSpecializations(@RequestParam Long programId) {
        Program prog = programRepository.findById(programId).orElse(null);
        if (prog == null) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(specializationRepository.findByProgram(prog).stream()
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
        // CRITICAL: filter by specialization FIRST so we get the correct semester IDs.
        // Each specialization has its own batch with its own semester IDs.
        // e.g. CSE Sem1=47, FSD Sem1=27 — different batches, different IDs.
        if (specializationId != null) {
            batches = batches.stream()
                    .filter(b -> b.getSpecialization() != null && specializationId.equals(b.getSpecialization().getId()))
                    .collect(Collectors.toList());
        }
        if (batchYear != null && !batchYear.isBlank() && !batchYear.equals("all")) {
            try { int fy = Integer.parseInt(batchYear);
                batches = batches.stream().filter(b -> { try { return fy == b.getStartYear(); } catch (Exception e) { return false; } }).collect(Collectors.toList());
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
                courses = courses.stream().filter(c -> { if (c.getBatch()==null) return false; try { return fy==c.getBatch().getStartYear(); } catch (Exception e) { return false; } }).collect(Collectors.toList());
            } catch (NumberFormatException ignored) {}
        }
        if (courseCode != null && !courseCode.isBlank())
            courses = courses.stream().filter(c -> courseCode.equalsIgnoreCase(c.getCourseCode())).collect(Collectors.toList());
        // Dedup: if structure parser still has duplicate entities for same course code, prefer the one with marks
        courses = deduplicateCourses(courses);

        List<Map<String, Object>> courseResults = new ArrayList<>();
        int totalStudents = 0, atRiskCount = 0;
        Map<String, List<Double>> branchPoAtt = new LinkedHashMap<>(), branchPsoAtt = new LinkedHashMap<>();

        for (Course course : courses) {
            List<StudentMark> marks = studentMarkRepository.findByCourse(course);
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

            // ── CO Attainment ────────────────────────────────────────────
            Map<String, Double> coAttMap = attainmentService.calculateCOAttainment(course.getId());
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

            List<Map<String, Object>> coPoRows = new ArrayList<>();
            for (CO co : cos) {
                Map<String, Object> row = new LinkedHashMap<>(); row.put("co", co.getCode());
                Map<String, Integer> weights = new HashMap<>();
                if (co.getId() != null) copoRepository.findByCoId(co.getId()).forEach(m -> weights.put(m.getPo().getCode(), m.getWeight()));
                for (String p : poHeaders) {
                    row.put(p, weights.getOrDefault(p, 0));
                }
                coPoRows.add(row);
            }
            Map<String, Double> poAttService = attainmentService.calculatePOAttainment(course.getId());
            Map<String, Double> poAttainment = new LinkedHashMap<>();
            for (String p : poHeaders) {
                double score0to3 = poAttService.getOrDefault(p, 0.0);
                double att = r1((score0to3 / 3.0) * 100.0);
                poAttainment.put(p, att); branchPoAtt.computeIfAbsent(p, k -> new ArrayList<>()).add(att);
            }

            // ── PSO Attainment ───────────────────────────────────────────
            List<String> psoHeaders = new ArrayList<>();
            Map<String, Double> psoAttainment = new LinkedHashMap<>();
            List<Map<String, Object>> coPsoRows = new ArrayList<>();
            if (prog != null) {
                List<PSO> psos = new ArrayList<>(psoRepository.findByProgram(prog));
                psos.removeIf(p -> p.getCode() == null || p.getCode().toUpperCase().contains("CODE"));
                psos.sort(Comparator.comparingInt(pso -> { try { return Integer.parseInt(pso.getCode().replaceAll("\\D+","")); } catch (Exception e) { return 999; } }));
                psoHeaders = psos.stream().map(PSO::getCode).collect(Collectors.toList());

                if (!psoHeaders.isEmpty()) {
                    for (CO co : cos) {
                        Map<String, Object> row = new LinkedHashMap<>(); row.put("co", co.getCode());
                        Map<String, Integer> psoWeights = new HashMap<>();
                        if (co.getId() != null) copsoRepository.findByCoId(co.getId()).forEach(m -> psoWeights.put(m.getPso().getCode(), m.getWeight()));
                        for (String ps : psoHeaders) row.put(ps, psoWeights.getOrDefault(ps, 0));
                        coPsoRows.add(row);
                    }
                    Map<String, Double> psoAttService = attainmentService.calculatePSOAttainment(course.getId());
                    for (String ps : psoHeaders) {
                        double score0to3 = psoAttService.getOrDefault(ps, 0.0);
                        double att = r1((score0to3 / 3.0) * 100.0);
                        psoAttainment.put(ps, att); branchPsoAtt.computeIfAbsent(ps, k -> new ArrayList<>()).add(att);
                    }
                }
            }

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id", course.getId()); res.put("courseCode", course.getCourseCode()); res.put("courseName", course.getCourseName());
            res.put("studentCount", byStudent.size()); res.put("avgAttainment", courseAvg);
            res.put("coAttainments", coAttainments); res.put("poHeaders", poHeaders);
            res.put("coPoMatrix", coPoRows); res.put("poAttainment", poAttainment);
            res.put("psoHeaders", psoHeaders); res.put("psoAttainment", psoAttainment);
            res.put("coPsoMatrix", coPsoRows);
            courseResults.add(res);
        }

        Map<String, Double> branchPoAvg = new LinkedHashMap<>(), branchPsoAvg = new LinkedHashMap<>();
        branchPoAtt.forEach((k,v) -> branchPoAvg.put(k, r1(v.stream().mapToDouble(d->d).average().orElse(0))));
        branchPsoAtt.forEach((k,v) -> branchPsoAvg.put(k, r1(v.stream().mapToDouble(d->d).average().orElse(0))));
        double overallAtt = courseResults.isEmpty() ? 0 : r1(courseResults.stream()
                .filter(c -> (double)c.get("avgAttainment") > 0)
                .mapToDouble(c -> (double)c.get("avgAttainment")).average().orElse(0));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totalCourses", courseResults.stream().filter(c -> (int)c.get("studentCount") > 0).count());
        resp.put("totalStudents", totalStudents);
        resp.put("atRiskStudents", atRiskCount); resp.put("overallAttainment", overallAtt);
        resp.put("branchPoAttainment", branchPoAvg); resp.put("branchPsoAttainment", branchPsoAvg);
        resp.put("courses", courseResults);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/courses")
    public ResponseEntity<?> getCoursesDropdown(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long specializationId,
            @RequestParam(required = false) Long programId,
            @RequestParam(required = false) String batchYear) {
        List<Course> courses = semesterId != null
                ? new ArrayList<>(courseRepository.findBySemesterId(semesterId))
                : getCoursesForFilter(programId, specializationId, batchYear);
        if (batchYear != null && !batchYear.isBlank() && !batchYear.equals("all")) {
            try { int fy = Integer.parseInt(batchYear);
                courses = courses.stream().filter(c -> c.getBatch() != null && fy == c.getBatch().getStartYear()).collect(Collectors.toList());
            } catch (NumberFormatException ignored) {}
        }
        // Deduplicate: pick the course entity with most marks when same code appears in multiple specializations
        courses = deduplicateCourses(courses);
        return ResponseEntity.ok(courses.stream()
                .map(c -> {
                    long studentCount = studentMarkRepository.findByCourse(c).stream()
                            .map(sm -> sm.getStudent().getId()).distinct().count();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("code", c.getCourseCode());
                    m.put("name", c.getCourseName());
                    m.put("studentCount", (int) studentCount);
                    return m;
                })
                .collect(Collectors.toList()));
    }

    @GetMapping("/students")
    public ResponseEntity<?> getStudentPerformance(
            @RequestParam(required = false) String courseCode,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String batchYear) {
        // Prefer courseId (exact match) over courseCode (may match wrong batch entity)
        Course course;
        if (courseId != null) {
            course = courseRepository.findById(courseId).orElse(null);
        } else if (courseCode != null) {
            course = courseRepository.findFirstByCourseCode(courseCode).orElse(null);
        } else {
            return ResponseEntity.ok(Map.of("error", "Provide courseId or courseCode"));
        }
        if (course == null) return ResponseEntity.ok(Map.of("error", "Course not found"));
        List<StudentMark> marks = new ArrayList<>(studentMarkRepository.findByCourse(course));
        if (batchYear != null && !batchYear.isBlank() && !batchYear.equals("all")) {
            try { int fy = Integer.parseInt(batchYear);
                marks = marks.stream().filter(sm -> { if (sm.getStudent()==null||sm.getStudent().getBatch()==null) return false; try { return fy==sm.getStudent().getBatch().getStartYear(); } catch (Exception e) { return false; } }).collect(Collectors.toList());
            } catch (NumberFormatException ignored) {}
        }
        List<CO> cos = new ArrayList<>(coRepository.findByCourse(course));
        cos.sort(Comparator.comparingInt(co -> { try { return Integer.parseInt(extractCoNum(co.getCode())); } catch (Exception e) { return 999; } }));
        if (marks.isEmpty()) return ResponseEntity.ok(Map.of("courseCode", course.getCourseCode() != null ? course.getCourseCode() : "", "courseName", course.getCourseName() != null ? course.getCourseName() : "", "students", List.of(), "coCodes", List.of()));
        if (cos.isEmpty()) {
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
        List<String> coCodes = cos.stream().map(CO::getCode).collect(Collectors.toList());
        Program prog = course.getProgram();
        List<PO> pos = prog != null ? new ArrayList<>(poRepository.findByProgram(prog)) : List.of();
        pos.sort(Comparator.comparingInt(po -> { try { return Integer.parseInt(po.getCode().replaceAll("\\D+","")); } catch (Exception e) { return 999; } }));
        Map<String, Map<String, Integer>> coPOW = new LinkedHashMap<>();
        for (CO co : cos) {
            Map<String, Integer> w = new HashMap<>();
            if (co.getId() != null) copoRepository.findByCoId(co.getId()).forEach(m -> w.put(m.getPo().getCode(), m.getWeight()));
            coPOW.put(co.getCode(), w);
        }
        Map<Long, Map<String, Double>> stAttService = attainmentService.calculateIndividualStudentCOAttainment(course.getId());
        Map<Long, List<StudentMark>> stMarksMap = marks.stream().collect(Collectors.groupingBy(sm -> sm.getStudent().getId()));
        
        List<Map<String, Object>> students = new ArrayList<>();
        for (Map.Entry<Long, List<StudentMark>> entry : stMarksMap.entrySet()) {
            Long sid = entry.getKey();
            StudentMark firstSm = entry.getValue().get(0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("enrollmentNo", firstSm.getStudent().getEnrollmentNumber());
            row.put("name", firstSm.getStudent().getName());
            
            Map<String, Double> coPctMap = stAttService.getOrDefault(sid, new HashMap<>());
            Map<String, Double> coAtt = new LinkedHashMap<>();
            for (String c : coCodes) {
                double pct = r1(coPctMap.getOrDefault(c, 0.0));
                row.put(c, pct); coAtt.put(c, pct);
            }
            
            // Calculate total obtained/max for overall column (without CO mapping limit, just raw sum)
            double totalObt = entry.getValue().stream().filter(sm -> sm.getMaxMarks() != null && sm.getMaxMarks() > 0).mapToDouble(sm -> sm.getMarks() != null ? sm.getMarks() : 0).sum();
            double totalMax = entry.getValue().stream().filter(sm -> sm.getMaxMarks() != null && sm.getMaxMarks() > 0).mapToDouble(StudentMark::getMaxMarks).sum();
            
            Map<String, Double> spa = new LinkedHashMap<>();
            for (PO po : pos) {
                double ws=0, wt=0;
                for (CO co : cos) {
                    int w = coPOW.getOrDefault(co.getCode(), Map.of()).getOrDefault(po.getCode(), 0);
                    if (w > 0) {
                        // Convert individual student's CO % to a level (0-3) using same threshold as AttainmentService
                        double coPct = coAtt.getOrDefault(co.getCode(), 0.0);
                        double coLevel = coPct >= 80.0 ? 3.0 : coPct >= 60.0 ? 2.0 : 1.0; // min level is 1
                        ws += coLevel * w;
                        wt += w;
                    }
                }
                // Return on 0-3 scale (like course-level PO attainment from AttainmentService)
                spa.put(po.getCode(), wt > 0 ? r1(ws / wt) : 0.0);
            }
            row.put("poAttainment", spa);
            double overall = totalMax>0 ? Math.min(100.0, r1(totalObt/totalMax*100.0)) : 0;
            row.put("overall", overall); row.put("status", overall>=40?"Pass":"At Risk");
            students.add(row);
        }
        students.sort(Comparator.comparing(s -> String.valueOf(s.get("enrollmentNo"))));
        String finalCode = course.getCourseCode() != null ? course.getCourseCode() : "";
        String finalName = course.getCourseName() != null ? course.getCourseName() : "";
        return ResponseEntity.ok(Map.of("courseCode", finalCode, "courseName", finalName,
                "coCodes", coCodes, "poHeaders", pos.stream().map(PO::getCode).collect(Collectors.toList()), "students", students));
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
            courses = courses.stream().filter(c -> c.getSpecialization()!=null && specializationId.equals(c.getSpecialization().getId())).collect(Collectors.toList());
        if (batchYear!=null && !batchYear.isBlank() && !batchYear.equals("all"))
            try { int fy=Integer.parseInt(batchYear);
                courses=courses.stream().filter(c -> { if(c.getBatch()==null) return false; try { return fy==c.getBatch().getStartYear(); } catch(Exception e){return false;} }).collect(Collectors.toList());
            } catch (NumberFormatException ignored) {}
        return courses;
    }

    /**
     * Deduplicates a list of courses by course code, preferring the entity with the most marks.
     *
     * GLOBAL FALLBACK: If a course entity in the list has 0 marks, this searches ALL course entities
     * with the same code in the database to find one that has marks.
     * This is needed because the structure parser creates one Course entity per specialization
     * for each course code, but marks were uploaded under only one of those entities (e.g. FSD copy).
     * The semester-filter gives us the CSE copy (correct code, wrong entity with no marks),
     * so we replace it with the FSD/null-spec copy that actually has marks.
     *
     * NOTE: we preserve the original course's metadata (name, specialization) for display,
     * only the course entity used for mark/CO/PO lookups changes.
     */
    private List<Course> deduplicateCourses(List<Course> courses) {
        Map<String, Course> best = new LinkedHashMap<>();
        Map<String, Long> bestCount = new HashMap<>();
        for (Course c : courses) {
            String code = c.getCourseCode();
            if (code == null) continue;
            long cnt = studentMarkRepository.findByCourse(c).size();
            // If this entity has no marks, search globally for a better copy
            if (cnt == 0) {
                List<Course> allCopies = courseRepository.findAllByCourseCode(code);
                for (Course alt : allCopies) {
                    long altCnt = studentMarkRepository.findByCourse(alt).size();
                    if (altCnt > cnt) { c = alt; cnt = altCnt; }
                }
            }
            if (!best.containsKey(code) || cnt > bestCount.getOrDefault(code, 0L)) {
                best.put(code, c);
                bestCount.put(code, cnt);
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
package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.example.entity.*;
import org.example.repository.*;
import org.example.util.EnrollmentCodeUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads the enrolment Excel file (format: "ADMITTED 2023-24" sheet with columns
 *   SR NO | Roll No | Student Name | Programme Name | UG/PG/DIPLOMA | School Name)
 * and assigns specialization_id to every matching Student row.
 *
 * This fixes the core data issue: students were ingested without a specialization,
 * so the spec-scoped filtering returned 0 students. Running this endpoint once
 * against the enrolment file stamps the correct specialization on every student.
 *
 * Endpoint: POST /students/assign-specialization  (multipart: file)
 */
@RestController
@RequestMapping("/students")
@RequiredArgsConstructor
public class StudentSpecializationController {

    private final StudentRepository        studentRepository;
    private final SpecializationRepository specializationRepository;
    private final ProgramRepository        programRepository;
    private final EnrollmentCodeUtil       enrollmentCodeUtil;

    // ── Programme-name → (programme-short-name, specialization-name-in-DB) ──────
    // IMPORTANT: Ordered most-specific first. Values MUST match specialization.name
    // in the DB (case-insensitive partial match is also tried as fallback).
    private static final Map<String, String[]> PROG_TO_SPEC = new LinkedHashMap<>();
    static {
        // BCA
        PROG_TO_SPEC.put("artificial intelligence and data science",
                new String[]{"BCA", "AI & Data Science [Honours/Honours with Research]"});
        PROG_TO_SPEC.put("bachelor of computer applications",
                new String[]{"BCA", "AI & Data Science [Honours/Honours with Research]"});
        // BTech specializations (most-specific first)
        PROG_TO_SPEC.put("artificial intelligence and machine learning",
                new String[]{"BTech", "AI & ML"});
        PROG_TO_SPEC.put("ai & ml",   new String[]{"BTech", "AI & ML"});
        PROG_TO_SPEC.put("samatrix",  new String[]{"BTech", "AI & ML"});
        PROG_TO_SPEC.put("sp inv",    new String[]{"BTech", "AI & ML"});
        PROG_TO_SPEC.put("full stack", new String[]{"BTech", "Full Stack Development"});
        PROG_TO_SPEC.put("xebia",     new String[]{"BTech", "Full Stack Development"});
        PROG_TO_SPEC.put("cyber security", new String[]{"BTech", "Cyber Security"});
        PROG_TO_SPEC.put("ec-council",    new String[]{"BTech", "Cyber Security"});
        PROG_TO_SPEC.put("data science",  new String[]{"BTech", "Data Science"});
        PROG_TO_SPEC.put("ibm",           new String[]{"BTech", "Data Science"});
        PROG_TO_SPEC.put("ux/ui",      new String[]{"BTech", "UI / UX"});
        PROG_TO_SPEC.put("(ux/ui)",    new String[]{"BTech", "UI / UX"});
        PROG_TO_SPEC.put("ux or ui",   new String[]{"BTech", "UI / UX"});
        PROG_TO_SPEC.put("imaginxp",   new String[]{"BTech", "UI / UX"});
        // Plain BTech CSE — must come AFTER all specialized keywords above
        PROG_TO_SPEC.put("bachelor of technology in computer science and engineering",
                new String[]{"BTech", "CSE"});
        // BSc
        PROG_TO_SPEC.put("bachelor of science (honours) in computer science",
                new String[]{"BSc", "Computer Science with IBM Collaboration"});
        PROG_TO_SPEC.put("bachelor of science (honours) in cyber security",
                new String[]{"BSc", "Cyber Security"});
        PROG_TO_SPEC.put("bachelor of science (honours) in data science",
                new String[]{"BSc", "Data Science"});
        // MCA and MTech — map to their single DB specialization
        PROG_TO_SPEC.put("master of computer applications",
                new String[]{"MCA", "Cse"});
        PROG_TO_SPEC.put("master of technology",
                new String[]{"MTech", "CSE"});
    }

    // ENROLL_CODE_TO_SPEC removed — use enrollmentCodeUtil.getCodeToSpecNameMap() instead.

    @PostMapping(value = "/assign-specialization", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<?> assignSpecializationFromExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "resetFirst", defaultValue = "false") boolean resetFirst) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors  = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, Integer> updatedBySpec = new LinkedHashMap<>();
        int totalRows = 0, updated = 0, notFound = 0, noSpec = 0;

        // ── Optional reset: clear all student specs before reassigning ────────────
        // Use resetFirst=true to fix students whose specialization was corrupted by
        // bad ingestion (e.g. all tagged as UI/UX). After reset, students not found
        // in the enrollment Excel will have null spec (shown in "All" view only).
        int resetCount = 0;
        if (resetFirst) {
            List<Student> allStudents = studentRepository.findAll();
            for (Student s : allStudents) {
                if (s.getSpecialization() != null) {
                    s.setSpecialization(null);
                    studentRepository.save(s);
                    resetCount++;
                }
            }
        }

        // Pre-load all specializations into a quick lookup map
        List<Specialization> allSpecs = specializationRepository.findAll();
        // specName (lower) → Specialization
        Map<String, Specialization> specByName = new LinkedHashMap<>();
        for (Specialization s : allSpecs) {
            if (s.getName() != null) {
                specByName.put(s.getName().trim().toLowerCase(), s);
            }
        }

        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheet("ADMITTED 2023-24");
            if (sheet == null) sheet = wb.getSheetAt(0);

            // ── Detect header row and column positions dynamically ────────────────
            int dataStartRow = 1;
            int rollNoCol    = 1;  // default fallback
            int progNameCol  = 3;  // default fallback

            for (int i = 0; i < Math.min(5, sheet.getLastRowNum()); i++) {
                Row hdr = sheet.getRow(i);
                if (hdr == null) continue;
                boolean foundRollNo = false;
                for (Cell c : hdr) {
                    String val = getString(c).trim().toLowerCase();
                    if (val.contains("roll") && val.contains("no")) {
                        rollNoCol    = c.getColumnIndex();
                        dataStartRow = i + 1;
                        foundRollNo  = true;
                    } else if (val.contains("programme") || val.contains("program")) {
                        progNameCol = c.getColumnIndex();
                    }
                }
                if (foundRollNo) break;
            }

            // Process each data row
            for (int i = dataStartRow; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                // Columns: detected dynamically above
                String rollNo        = getString(row.getCell(rollNoCol)).trim();
                String programmeName = getString(row.getCell(progNameCol)).trim();

                if (rollNo.isBlank()) continue;
                // Normalise roll no: remove .0 suffix from numeric cells
                String enrollment = rollNo.replaceAll("\\.0$", "").trim();
                if (enrollment.isBlank()) continue;
                totalRows++;

                // ── Step 1: resolve spec from programme name via PROG_TO_SPEC ─────
                Specialization spec = null;
                if (!programmeName.isBlank()) {
                    spec = resolveSpecialization(programmeName, specByName);
                }

                // ── Step 2: fallback — use enrollment number digits 4-5 ──────────
                // If the programme name didn't yield a match, try the 2-digit code at
                // enrollment[4..5]. EnrollmentCodeUtil maps these codes to spec names.
                if (spec == null && enrollment.length() >= 6) {
                    String code = enrollment.substring(4, 6);
                    Map<String, String> codeMap = enrollmentCodeUtil.getCodeToSpecNameMap();
                    if (codeMap.containsKey(code)) {
                        String specName = codeMap.get(code);
                        if (specName != null) {
                            // Find spec by name (case-insensitive partial match)
                            final String sn = specName.toLowerCase();
                            spec = specByName.get(sn);
                            if (spec == null) {
                                spec = specByName.entrySet().stream()
                                    .filter(e -> e.getKey().contains(sn) || sn.contains(e.getKey()))
                                    .map(Map.Entry::getValue).findFirst().orElse(null);
                            }
                        }
                        // code maps to null spec → valid student but no spec (plain CSE/BCA/BSc)
                        // leave spec=null, will be counted as noSpec but not an error
                    }
                }

                if (spec == null) {
                    noSpec++;
                    if (skipped.size() < 20)
                        skipped.add("Row " + (i+1) + ": no spec match for '" + programmeName
                            + "' enroll='" + enrollment + "'");
                    continue;
                }

                // Find student by enrollment number (enrollment already normalised above)
                Optional<Student> studentOpt = studentRepository.findByEnrollmentNumber(enrollment);
                if (studentOpt.isEmpty()) {
                    notFound++;
                    continue;
                }

                Student student = studentOpt.get();
                // Always overwrite — enrollment file is the ground truth
                if (!spec.equals(student.getSpecialization())) {
                    student.setSpecialization(spec);
                    studentRepository.save(student);
                    updated++;
                    updatedBySpec.merge(spec.getName(), 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to process file: " + e.getMessage()));
        }

        result.put("status", "success");
        if (resetFirst) result.put("students_reset", resetCount);
        result.put("total_rows_in_file", totalRows);
        result.put("students_updated", updated);
        result.put("students_not_in_db", notFound);
        result.put("rows_without_spec_match", noSpec);
        result.put("updated_by_specialization", updatedBySpec);
        if (!skipped.isEmpty()) {
            result.put("skipped_samples", skipped.subList(0, Math.min(10, skipped.size())));
        }
        long unassigned = studentRepository.countUnassignedStudents();
        result.put("students_still_without_spec", unassigned);
        return ResponseEntity.ok(result);
    }

    /**
     * Auto-assigns specializations based on the enrollment number pattern.
     * Digits 5-6 of the enrollment number encode the branch/specialization.
     * This endpoint discovers the mapping by comparing student counts in each
     * code-group against the known specialization sizes in the DB.
     *
     * GET  /students/assign-specialization-preview  — shows the proposed mapping without updating
     * POST /students/assign-specialization-auto     — applies the mapping
     */
    @GetMapping("/assign-specialization-preview")
    @Transactional(readOnly = true)
    public ResponseEntity<?> previewAutoAssign() {
        return ResponseEntity.ok(buildEnrollmentMapping());
    }

    @PostMapping("/assign-specialization-auto")
    @Transactional
    public ResponseEntity<?> autoAssignFromEnrollmentNumber() {
        Map<String, Object> mapping = buildEnrollmentMapping();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) mapping.get("groups");

        int totalUpdated = 0;
        Map<String, Integer> updatedBySpec = new LinkedHashMap<>();

        for (Map<String, Object> group : groups) {
            Object specIdObj = group.get("assigned_spec_id");
            if (specIdObj == null) continue;
            Long specId = Long.parseLong(specIdObj.toString());
            String code = group.get("enrollment_code").toString();
            String progName = group.get("program").toString();
            int startYear = Integer.parseInt(group.get("start_year").toString());

            Optional<Specialization> specOpt = specializationRepository.findById(specId);
            if (specOpt.isEmpty()) continue;
            Specialization spec = specOpt.get();

            // Find students matching this code/program/year
            List<Student> students = studentRepository.findAll().stream()
                .filter(s -> {
                    String enr = s.getEnrollmentNumber();
                    if (enr == null || enr.length() < 6) return false;
                    if (!code.equals(enr.substring(4, 6))) return false;
                    if (s.getBatch() == null || s.getBatch().getStartYear() != startYear) return false;
                    if (s.getProgram() == null || !progName.equals(s.getProgram().getName())) return false;
                    return true;
                }).collect(java.util.stream.Collectors.toList());

            for (Student s : students) {
                s.setSpecialization(spec);
                studentRepository.save(s);
                totalUpdated++;
                updatedBySpec.merge(spec.getName(), 1, Integer::sum);
            }
        }

        return ResponseEntity.ok(Map.of(
            "status", "success",
            "total_updated", totalUpdated,
            "by_specialization", updatedBySpec
        ));
    }

    /**
     * Builds a mapping of enrollment code → specialization by comparing group sizes.
     * The enrollment number encodes specialization in digits 5-6 (0-indexed: chars 4-5).
     */
    private Map<String, Object> buildEnrollmentMapping() {
        // Get all specializations grouped by program
        Map<String, List<Specialization>> specsByProg = specializationRepository.findAll().stream()
            .filter(s -> s.getProgram() != null)
            .collect(java.util.stream.Collectors.groupingBy(s -> s.getProgram().getName()));

        // Get all students grouped by (program, startYear, code)
        Map<String, Long> groupCounts = new LinkedHashMap<>();
        Map<String, String> groupProg = new LinkedHashMap<>();
        Map<String, Integer> groupYear = new LinkedHashMap<>();

        studentRepository.findAll().forEach(s -> {
            String enr = s.getEnrollmentNumber();
            if (enr == null || enr.length() < 6) return;
            String code = enr.substring(4, 6);
            String prog = s.getProgram() != null ? s.getProgram().getName() : "?";
            int year = s.getBatch() != null ? s.getBatch().getStartYear() : 0;
            String key = prog + "_" + year + "_" + code;
            groupCounts.merge(key, 1L, Long::sum);
            groupProg.put(key, prog);
            groupYear.put(key, year);
        });

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, Long> e : groupCounts.entrySet()) {
            String key = e.getKey();
            long count = e.getValue();
            String prog = groupProg.get(key);
            int year = groupYear.get(key);
            String code = key.substring(key.lastIndexOf('_') + 1);

            // Try to find the best-matching spec by comparing student count
            List<Specialization> candidates = specsByProg.getOrDefault(prog, List.of());
            Long bestSpecId = null;
            String bestSpecName = null;
            long bestDiff = Long.MAX_VALUE;
            for (Specialization sp : candidates) {
                long spCount = studentRepository.findBySpecialization(sp).size();
                if (spCount == 0) continue;
                long diff = Math.abs(spCount - count);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestSpecId = sp.getId();
                    bestSpecName = sp.getName();
                }
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enrollment_code", code);
            m.put("program", prog);
            m.put("start_year", year);
            m.put("student_count", count);
            m.put("assigned_spec_id", bestSpecId);
            m.put("assigned_spec_name", bestSpecName);
            m.put("confidence_diff", bestSpecId != null ? bestDiff : -1);
            groups.add(m);
        }

        return Map.of("groups", groups, "note",
            "Review 'assigned_spec_name' for each code. 'confidence_diff' near 0 = high confidence.");
    }


    @PostMapping("/assign-specialization-manual")
    @Transactional
    public ResponseEntity<?> assignManual(@RequestBody List<Map<String, Object>> assignments) {
        int ok = 0, missing = 0;
        for (Map<String, Object> a : assignments) {
            String enr = String.valueOf(a.get("enrollmentNumber"));
            Long specId = Long.valueOf(String.valueOf(a.get("specializationId")));
            Optional<Student> st = studentRepository.findByEnrollmentNumber(enr);
            Optional<Specialization> sp = specializationRepository.findById(specId);
            if (st.isPresent() && sp.isPresent()) {
                st.get().setSpecialization(sp.get());
                studentRepository.save(st.get());
                ok++;
            } else {
                missing++;
            }
        }
        return ResponseEntity.ok(Map.of("updated", ok, "not_found", missing));
    }

    /**
     * GET /students/specialization-status — shows how many students have/haven't got a spec.
     */
    @GetMapping("/specialization-status")
    public ResponseEntity<?> status() {
        long total      = studentRepository.count();
        long unassigned = studentRepository.countUnassignedStudents();
        List<Specialization> specs = specializationRepository.findAll();
        List<Map<String, Object>> perSpec = specs.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("programme", s.getProgram() != null ? s.getProgram().getName() : null);
            m.put("students", studentRepository.findBySpecialization(s).size());
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of(
                "total_students", total,
                "without_specialization", unassigned,
                "with_specialization", total - unassigned,
                "per_specialization", perSpec
        ));
    }

    /**
     * Resolves a Specialization from a programme name string using PROG_TO_SPEC
     * as the single source of truth. Most-specific keywords are checked first.
     */
    private Specialization resolveSpecialization(String programmeName, Map<String, Specialization> specByName) {
        String lower = programmeName.toLowerCase();

        for (Map.Entry<String, String[]> entry : PROG_TO_SPEC.entrySet()) {
            String keyword = entry.getKey();
            String[] mapping = entry.getValue();
            String specName = mapping[1]; // index 1 = specialization name (null for plain programs)

            if (!lower.contains(keyword)) continue;
            if (specName == null) return null; // plain program with no named sub-spec

            // Find the matching Specialization entity in the DB
            Specialization spec = specByName.get(specName.trim().toLowerCase());
            if (spec != null) return spec;

            // Partial match: DB spec name may differ slightly from PROG_TO_SPEC value
            final String sn = specName.trim().toLowerCase();
            return specByName.entrySet().stream()
                .filter(e -> e.getKey().contains(sn) || sn.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
        }
        return null;
    }

    private String getString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                yield String.valueOf((long) v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield String.valueOf((long) cell.getNumericCellValue()); }
                catch (Exception e) { yield cell.getStringCellValue(); }
            }
            default -> "";
        };
    }
}

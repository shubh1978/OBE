package org.example.service;

import lombok.RequiredArgsConstructor;
import org.example.entity.*;
import org.example.repository.*;
import org.example.util.EnrollmentCodeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.util.*;

/**
 * ── ResultExcelIngestionService ──────────────────────────────────────────────
 *
 * Parses university "Result Odd Sem 2025-26.xlsx" (Sheet2) and ingests
 * Internal (mid_term, max=20) and External (end_term, max=50) marks into StudentMark.
 *
 * Sheet2 column layout (0-based POI indices):
 *   0  Roll No          — enrollment number
 *   2  Student Name
 *   4  Program Name     — e.g. "B.Tech CSE (Cyber Security) EC-Council"
 *   6  Semester         — e.g. "Sem 3"
 *   9  Course Code      (has leading space in header)
 *  13  Internal Marks   → examType="mid_term",  maxMarks=20
 *  14  External Marks   → examType="end_term",  maxMarks=50
 *  17  Grade            — "AB" = absent → skip row
 *
 * ✅ LOCAL  : currently active — hits localhost:5432/obe
 * 🚀 PROD   : no code changes needed — uses env-var DB config in application.properties
 *
 * Deduplication: on re-upload, existing marks (matched by studentId|question|examType)
 * are NOT duplicated. Only genuinely new rows are inserted.
 */
@Service
@RequiredArgsConstructor
public class ResultExcelIngestionService {

    // ── Sheet name ────────────────────────────────────────────────────────────
    private static final String SHEET_NAME = "Sheet2";

    // ── Column indices (0-based) ──────────────────────────────────────────────
    private static final int COL_ROLL_NO      = 0;
    private static final int COL_STUDENT_NAME = 2;
    private static final int COL_PROGRAM      = 4;
    private static final int COL_SEMESTER     = 6;
    private static final int COL_COURSE_CODE  = 9;
    private static final int COL_INTERNAL     = 13;   // mid_term
    private static final int COL_EXTERNAL     = 14;   // end_term
    private static final int COL_GRADE        = 17;   // "AB" = absent

    // ── Max marks per exam type ───────────────────────────────────────────────
    // ✅ CHANGE HERE if university changes max marks scheme
    private static final double MAX_MID_TERM = 20.0;
    private static final double MAX_END_TERM = 50.0;

    // Question label — same "Q1" convention as ZipIngestionService
    private static final String QUESTION_LABEL = "Q1";

    private final ProgramRepository           programRepository;
    private final SpecializationRepository    specializationRepository;
    private final BatchRepository             batchRepository;
    private final SemesterRepository          semesterRepository;
    private final CourseRepository            courseRepository;
    private final StudentRepository           studentRepository;
    private final StudentMarkRepository       studentMarkRepository;
    private final QuestionCOMappingRepository questionCOMappingRepository;
    private final CORepository                coRepository;
    private final AttainmentService           attainmentService;
    private final EnrollmentCodeUtil          enrollmentCodeUtil;

    // Cache: prevent duplicate Q-CO mapping creation within a single upload call
    private final Map<String, Boolean> mappingCreatedCache = new HashMap<>();

    // =========================================================================
    //  MAIN ENTRY
    // =========================================================================
    @Transactional  // keeps JPA session open for the entire upload → prevents LazyInitializationException
    public Map<String, Object> ingest(MultipartFile file) throws Exception {
        mappingCreatedCache.clear();

        int rowsRead = 0, rowsSaved = 0, rowsAbsent = 0;
        int rowsNoProgram = 0, rowsNoCourse = 0, rowsDuplicate = 0;
        List<String> errors = new ArrayList<>();
        String resolvedSheetName = "(unknown)";  // filled in once workbook is opened

        // courseKey → courseId  (for attainment recalc after all rows are saved)
        Map<String, Long> processedCourses = new LinkedHashMap<>();

        // Per-course dedup set: loaded lazily first time we see a course
        // Key:   courseKey ("ENCS201_BTech_2024")
        // Value: Set<"studentId|question|examType">
        Map<String, Set<String>> existingKeysPerCourse = new HashMap<>();

        // In-memory caches to avoid N+1 DB lookups
        Map<String, Program>  programCache = new HashMap<>();
        Map<String, Course>   courseCache  = new HashMap<>();
        Map<String, Student>  studentCache = new HashMap<>();
        Map<String, Batch>    batchCache   = new HashMap<>();

        // Write buffer — flush every 500 records
        List<StudentMark> writeBuffer = new ArrayList<>();

        try (InputStream is = file.getInputStream();
             Workbook wb  = new XSSFWorkbook(is)) {

            // ── Auto-detect the marks sheet ───────────────────────────────────
            // Primary: look for a sheet containing "Internal Marks" or "External Marks"
            // in its header row.  Falls back to first sheet.
            // This avoids hard-coding "Sheet2" so that files with different sheet names work too.
            Sheet sheet = null;
            String detectedSheetName = null;  // local, assigned to outer resolvedSheetName

            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet candidate = wb.getSheetAt(si);
                Row hdr = candidate.getRow(0);
                if (hdr == null) continue;
                for (int ci = 0; ci < hdr.getLastCellNum(); ci++) {
                    String h = str(hdr.getCell(ci)).toLowerCase();
                    if (h.contains("internal") || h.contains("external marks")) {
                        sheet = candidate;
                        detectedSheetName = wb.getSheetName(si);
                        break;
                    }
                }
                if (sheet != null) break;
            }

            // If no marks-style sheet found, try the canonical "Sheet2" name
            if (sheet == null && wb.getSheet(SHEET_NAME) != null) {
                sheet = wb.getSheet(SHEET_NAME);
                detectedSheetName = SHEET_NAME;
            }

            // Last resort: use whichever sheet the file has (process will gracefully skip rows)
            if (sheet == null && wb.getNumberOfSheets() > 0) {
                sheet = wb.getSheetAt(0);
                detectedSheetName = wb.getSheetName(0);
                System.out.println("[ResultExcel] ⚠ No 'Internal Marks' column found. " +
                                   "Processing first sheet '" + detectedSheetName + "' — rows without valid " +
                                   "enrollment/program/marks data will be skipped automatically.");
            }

            if (sheet == null) {
                throw new IllegalStateException("Workbook contains no sheets.");
            }

            resolvedSheetName = detectedSheetName;  // propagate to outer scope
            System.out.println("[ResultExcel] Processing sheet='" + resolvedSheetName
                               + "' totalRows=" + sheet.getLastRowNum());



            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                // ── Skip absent ───────────────────────────────────────────────
                String grade = str(row.getCell(COL_GRADE));
                if ("AB".equalsIgnoreCase(grade.trim())) { rowsAbsent++; continue; }

                rowsRead++;

                String enrollmentNo  = str(row.getCell(COL_ROLL_NO)).trim();
                String studentName   = str(row.getCell(COL_STUDENT_NAME)).trim();
                String programStr    = str(row.getCell(COL_PROGRAM)).trim();
                String semesterStr   = str(row.getCell(COL_SEMESTER)).trim();
                String courseCodeRaw = str(row.getCell(COL_COURSE_CODE)).trim();

                if (enrollmentNo.isEmpty() || courseCodeRaw.isEmpty()) continue;

                // ── Resolve Program ───────────────────────────────────────────
                String progName = normalizeProgram(programStr);
                if (progName == null) { rowsNoProgram++; continue; }

                Program program = programCache.computeIfAbsent(progName,
                    name -> programRepository.findByName(name).orElse(null));
                if (program == null) { rowsNoProgram++; continue; }

                // ── Resolve Course ────────────────────────────────────────────
                String courseCode  = courseCodeRaw.toUpperCase();
                int batchStartYear = parseBatchYearFromEnrollment(enrollmentNo);
                final Program fp   = program;
                String courseKey   = courseCode + "_" + progName + "_" + batchStartYear;

                Course course = courseCache.computeIfAbsent(courseKey, k -> {
                    List<Course> all = courseRepository.findByProgram(fp);
                    // 1st: exact match on code + batch start year
                    Course found = all.stream()
                        .filter(c -> courseCode.equalsIgnoreCase(c.getCourseCode()))
                        .filter(c -> c.getBatch() != null
                                  && c.getBatch().getStartYear() != null
                                  && c.getBatch().getStartYear() == batchStartYear)
                        .findFirst().orElse(null);
                    if (found != null) return found;
                    // 2nd fallback: code match under same program, any batch
                    found = all.stream()
                        .filter(c -> courseCode.equalsIgnoreCase(c.getCourseCode()))
                        .findFirst().orElse(null);
                    if (found != null)
                        System.out.println("  [FALLBACK] " + courseCode + " matched without batch year");
                    else
                        System.out.println("  ✗ [SKIP] Course not in DB: " + courseCode
                                           + " prog=" + fp.getName());
                    return found;
                });

                if (course == null) { rowsNoCourse++; continue; }

                // ── Resolve Batch (from course first) ─────────────────────────
                Batch batchObj = course.getBatch();
                if (batchObj == null) {
                    String batchKey = progName + "_" + batchStartYear;
                    final int fYear = batchStartYear;
                    batchObj = batchCache.computeIfAbsent(batchKey, k ->
                        batchRepository.findByStartYearAndProgramAndSpecialization(
                            fYear, fp, null).orElse(null));
                }
                if (batchObj == null) { rowsNoCourse++; continue; }

                // ── Lazy-load dedup keys for this course ──────────────────────
                if (!existingKeysPerCourse.containsKey(courseKey)) {
                    Set<String> keys = new HashSet<>();
                    studentMarkRepository.findByCourseIdWithStudent(course.getId())
                        .forEach(sm -> {
                            if (sm.getStudent() != null && sm.getQuestion() != null
                                                       && sm.getExamType() != null) {
                                keys.add(sm.getStudent().getId() + "|"
                                       + sm.getQuestion() + "|"
                                       + sm.getExamType());
                            }
                        });
                    existingKeysPerCourse.put(courseKey, keys);
                    System.out.println("  [DEDUP] Loaded " + keys.size()
                                       + " existing marks for " + courseKey);
                }
                Set<String> existingKeys = existingKeysPerCourse.get(courseKey);

                // ── Auto-create Q1→CO1 QuestionCOMapping if absent ────────────
                ensureQuestionCOMapping(course);

                // ── Resolve Specialization ────────────────────────────────────
                Specialization spec = resolveSpecFromEnrollment(enrollmentNo, program);
                if (spec == null) spec = batchObj.getSpecialization();
                final Specialization fSpec  = spec;
                final Batch          fBatch = batchObj;
                final Program        fProg  = program;
                final String         fName  = studentName;

                // ── Resolve / create Student ──────────────────────────────────
                Student student = studentCache.computeIfAbsent(enrollmentNo, id -> {
                    Student ex = studentRepository.findByEnrollmentNumber(id).orElse(null);
                    if (ex != null) {
                        boolean wrongProg = ex.getSpecialization() != null
                            && ex.getSpecialization().getProgram() != null
                            && !ex.getSpecialization().getProgram().getId().equals(fProg.getId());
                        if ((ex.getSpecialization() == null || wrongProg) && fSpec != null) {
                            ex.setSpecialization(fSpec);
                            return studentRepository.save(ex);
                        }
                        return ex;
                    }
                    Student s = new Student();
                    s.setEnrollmentNumber(id);
                    s.setName(fName);
                    s.setProgram(fProg);
                    s.setBatch(fBatch);
                    s.setSpecialization(fSpec);
                    return studentRepository.save(s);
                });

                // ── Build marks ───────────────────────────────────────────────
                Double internalMarks = numericOrNull(row.getCell(COL_INTERNAL));
                Double externalMarks = numericOrNull(row.getCell(COL_EXTERNAL));
                int newThisRow = 0;

                // mid_term (Internal, max=20)
                if (internalMarks != null) {
                    String dk = student.getId() + "|" + QUESTION_LABEL + "|mid_term";
                    if (!existingKeys.contains(dk)) {
                        StudentMark sm = buildMark(student, course, semesterStr,
                                                   "mid_term", "Mid Term Examination",
                                                   internalMarks, MAX_MID_TERM);
                        writeBuffer.add(sm);
                        existingKeys.add(dk);
                        newThisRow++;
                    } else { rowsDuplicate++; }
                }

                // end_term (External, max=50)
                if (externalMarks != null) {
                    String dk = student.getId() + "|" + QUESTION_LABEL + "|end_term";
                    if (!existingKeys.contains(dk)) {
                        StudentMark sm = buildMark(student, course, semesterStr,
                                                   "end_term", "End Term Examination",
                                                   externalMarks, MAX_END_TERM);
                        writeBuffer.add(sm);
                        existingKeys.add(dk);
                        newThisRow++;
                    } else { rowsDuplicate++; }
                }

                if (newThisRow > 0)
                    processedCourses.put(courseKey, course.getId());

                // Flush buffer
                if (writeBuffer.size() >= 500) {
                    studentMarkRepository.saveAll(writeBuffer);
                    rowsSaved += writeBuffer.size();
                    writeBuffer.clear();
                }
            } // end row loop
        } // end try-with-resources (workbook closed here)

        // Flush remainder
        if (!writeBuffer.isEmpty()) {
            studentMarkRepository.saveAll(writeBuffer);
            rowsSaved += writeBuffer.size();
        }

        System.out.println("[ResultExcel] Done — saved=" + rowsSaved
                           + " absent=" + rowsAbsent
                           + " noProgram=" + rowsNoProgram
                           + " noCourse=" + rowsNoCourse
                           + " duplicatesSkipped=" + rowsDuplicate);

        // ── Recalculate attainment for all touched courses ────────────────────
        Map<String, Object> attainmentSummary = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : processedCourses.entrySet()) {
            try {
                Map<String, Object> att = attainmentService.getAttainmentReport(e.getValue());
                Course c = courseRepository.findById(e.getValue()).orElse(null);
                String key = c != null ? c.getCourseCode() : "Course_" + e.getValue();
                attainmentSummary.put(key, att);
            } catch (Exception ex) {
                System.err.println("Attainment calc failed for " + e.getKey()
                                   + ": " + ex.getMessage());
            }
        }

        // ── Build response ────────────────────────────────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status",                errors.isEmpty() ? "SUCCESS" : "PARTIAL");
        result.put("message",               "Result Excel processed successfully");
        result.put("sheet_processed",       resolvedSheetName);
        result.put("rows_read",             rowsRead);
        result.put("mark_records_saved",    rowsSaved);
        result.put("rows_absent_skipped",   rowsAbsent);
        result.put("rows_no_program_skipped", rowsNoProgram);
        result.put("rows_no_course_skipped",  rowsNoCourse);
        result.put("duplicates_skipped",    rowsDuplicate);
        result.put("courses_processed",     processedCourses.size());
        result.put("mid_term_max_marks",    MAX_MID_TERM);
        result.put("end_term_max_marks",    MAX_END_TERM);
        if (!errors.isEmpty())             result.put("errors", errors);
        if (!attainmentSummary.isEmpty())  result.put("attainment_summary", attainmentSummary);
        return result;
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private StudentMark buildMark(Student student, Course course, String period,
                                  String examType, String eventName,
                                  double marks, double maxMarks) {
        StudentMark sm = new StudentMark();
        sm.setStudent(student);
        sm.setCourse(course);
        sm.setQuestion(QUESTION_LABEL);
        sm.setMarks(clamp(marks, maxMarks));
        sm.setMaxMarks(maxMarks);
        sm.setEventMaxMarks(maxMarks);
        sm.setExamType(examType);
        sm.setPeriod(period);
        sm.setEventName(eventName);
        return sm;
    }

    /** Auto-create QuestionCOMapping Q1 → CO1 for this course if not already present. */
    private void ensureQuestionCOMapping(Course course) {
        String cacheKey = course.getId() + "_" + QUESTION_LABEL;
        if (mappingCreatedCache.containsKey(cacheKey)) return;
        try {
            String fullCode  = course.getCourseCode() + "-CO1";
            String shortCode = "CO1";
            CO co = coRepository.findByCodeAndCourse(fullCode, course)
                .orElseGet(() -> coRepository.findByCodeAndCourse(shortCode, course)
                .orElseGet(() -> {
                    CO newCo = new CO();
                    newCo.setCode(fullCode);
                    newCo.setDescription("Auto-created from result-excel upload");
                    newCo.setCourse(course);
                    return coRepository.save(newCo);
                }));
            boolean exists = questionCOMappingRepository
                .findByCourseId(course.getId()).stream()
                .anyMatch(m -> QUESTION_LABEL.equalsIgnoreCase(m.getQuestionLabel())
                            && m.getCo().getId().equals(co.getId()));
            if (!exists) {
                QuestionCOMapping m = new QuestionCOMapping();
                m.setCourse(course);
                m.setQuestionLabel(QUESTION_LABEL);
                m.setCo(co);
                m.setMaxMarks(MAX_MID_TERM + MAX_END_TERM); // 70 total
                questionCOMappingRepository.save(m);
            }
        } catch (Exception e) {
            System.err.println("Warning: Q-CO mapping failed for course="
                               + course.getCourseCode() + ": " + e.getMessage());
        }
        mappingCreatedCache.put(cacheKey, true);
    }

    /**
     * Map university program string → DB program name.
     * Returns null for programs not tracked in our DB (B.Com, BBA, Law, etc.).
     *
     * ✅ LOCAL / 🚀 PROD: no changes needed for env switch.
     */
    private String normalizeProgram(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String up = raw.toUpperCase();
        if (up.contains("B.TECH") || up.contains("BTECH")
                || up.contains("BACHELOR OF TECHNOLOGY")) return "BTech";
        if (up.contains("B.SC") || up.contains("BSC")
                || up.contains("BACHELOR OF SCIENCE")) return "BSc";
        if (up.contains("BCA")
                || up.contains("BACHELOR OF COMPUTER APPLICATIONS")) return "BCA";
        if (up.contains("MCA")
                || up.contains("MASTER OF COMPUTER APPLICATIONS")) return "MCA";
        if (up.contains("MTECH") || up.contains("M.TECH")
                || up.contains("MASTER OF TECHNOLOGY")) return "MTech";
        return null; // B.Com, BBA, Law, MBA, etc. → skip
    }

    /** "Sem 3" → 3,  "Sem 1" → 1 */
    private int parseSemesterNum(String s) {
        if (s == null) return 1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : 1;
    }

    /**
     * Extract 4-digit batch start year from enrollment number.
     * Format: YYPPSSNNNN — first 2 digits = year suffix → 20YY.
     * E.g. "2401410004" → "24" → 2024.
     */
    private int parseBatchYearFromEnrollment(String enr) {
        if (enr == null || enr.length() < 2) return 2024;
        try { return 2000 + Integer.parseInt(enr.substring(0, 2)); }
        catch (NumberFormatException e) { return 2024; }
    }

    /** Resolve Specialization via enrollment code (digits 4-5). */
    private Specialization resolveSpecFromEnrollment(String enrollmentNo, Program program) {
        try {
            String specCode = enrollmentCodeUtil.extractSpecCode(enrollmentNo);
            String specName = specCode != null
                    ? enrollmentCodeUtil.getSpecNameForCode(specCode) : null;
            if (specName == null) return null;
            final String fsn = specName;
            return specializationRepository.findByProgram(program).stream()
                .filter(s -> fsn.equalsIgnoreCase(s.getName())
                          || s.getName().toLowerCase().contains(fsn.toLowerCase())
                          || fsn.toLowerCase().contains(s.getName().toLowerCase()))
                .findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    private double clamp(double val, double max) {
        if (val < 0)   return 0;
        if (val > max) return max;
        return val;
    }

    private String str(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                yield (v == Math.floor(v) && !Double.isInfinite(v))
                      ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield String.valueOf(cell.getNumericCellValue()); }
                catch (Exception e) {
                    try { yield cell.getStringCellValue(); }
                    catch (Exception e2) { yield ""; }
                }
            }
            default -> "";
        };
    }

    private Double numericOrNull(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.NUMERIC)
            return cell.getNumericCellValue();
        if (cell.getCellType() == CellType.STRING) {
            String s = cell.getStringCellValue().trim();
            if (s.isEmpty()) return null;
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        }
        if (cell.getCellType() == CellType.FORMULA) {
            try { return cell.getNumericCellValue(); } catch (Exception e) { return null; }
        }
        return null;
    }
}

package org.example.service;

import lombok.RequiredArgsConstructor;
import org.example.entity.*;
import org.example.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ── QuestionWiseReportIngestionService ───────────────────────────────────────
 *
 * Parses the "Question wise marks entry report" Excel (Sheet2).
 *
 * Column layout (0-based POI indices):
 *   0  Sr.no
 *   1  Admission No.
 *   2  Roll no
 *   4  Name
 *   7  Course Code
 *  13  Class Name  — "B.Tech CSE (AI & ML)_2025-2029_1_Sem 1"
 *  14  Semester Name
 *  15  Component Name
 *  16  Frequency No
 *  17+ Q1, Q2, Q3…
 *
 * Mid-term logic:
 *   "Class Test …" component → sum ALL Q values in the row → store as single
 *   question label "MID" with maxMarks=20. Only Freq 1 is processed (Freq 2 skip).
 *   EXCEPTION: if all Q values are 0 or absent → skip (no marks entered).
 *   If class test has marks in only ONE Q column → still stored as MID.
 *   If class test has marks in MULTIPLE Q columns → MID = sum (proper question-wise test).
 *
 * End-term logic:
 *   "End Term Examination" → individual Q marks stored with labels Q1, Q2 … etc.
 *
 * Specialization routing:
 *   Parsed from class name parentheses, e.g. "(AI & ML)" → AIML spec.
 *   Service looks for a course matching code + batch + specialization.
 *   Falls back to code + batch (any spec) if no spec-specific course found.
 *
 * Auto Q-CO mapping:
 *   If a course has no Q-CO mappings after marks are saved, they are created
 *   automatically based on first CO found for that course.
 */
@Service
@RequiredArgsConstructor
public class QuestionWiseReportIngestionService {

    // ── Column constants (0-based) ────────────────────────────────────────────
    private static final int COL_ROLL_NO     = 2;
    private static final int COL_NAME        = 4;
    private static final int COL_COURSE_CODE = 7;
    private static final int COL_CLASS_NAME  = 13;
    private static final int COL_SEM_NAME    = 14;
    private static final int COL_COMPONENT   = 15;
    private static final int COL_FREQ_NO     = 16;
    private static final int COL_Q_START     = 17;

    // ── Max marks ─────────────────────────────────────────────────────────────
    private static final double MAX_MID_TERM = 20.0;
    private static final double MAX_END_TERM = 50.0;

    // ── Spec hint → canonical DB name mappings ────────────────────────────────
    // Key: UPPERCASE normalized hint from class name. Value: partial DB name to match.
    private static final Map<String, String> SPEC_HINT_MAP = new LinkedHashMap<>();
    static {
        // BTech
        SPEC_HINT_MAP.put("AI & ML",        "AI");          // matches "AI & ML", "AIML"
        SPEC_HINT_MAP.put("AIML",           "AI");
        SPEC_HINT_MAP.put("AI&ML",          "AI");
        SPEC_HINT_MAP.put("AI AND ML",      "AI");
        SPEC_HINT_MAP.put("ARTIFICIAL INTELLIGENCE AND MACHINE LEARNING", "AI");
        SPEC_HINT_MAP.put("ARTIFICIAL INTELLIGENCE", "AI");
        SPEC_HINT_MAP.put("FULL STACK",     "Full Stack");   // matches "Full Stack Development"
        SPEC_HINT_MAP.put("FULL STACK DEVELOPMENT", "Full Stack");
        SPEC_HINT_MAP.put("FSD",            "Full Stack");
        SPEC_HINT_MAP.put("UX OR UI",       "UI");           // matches "UI / UX", "UIUX"
        SPEC_HINT_MAP.put("UX/UI",          "UI");
        SPEC_HINT_MAP.put("UIUX",           "UI");
        SPEC_HINT_MAP.put("UI UX",          "UI");
        SPEC_HINT_MAP.put("USER INTERFACE", "UI");
        SPEC_HINT_MAP.put("IMAGINXP",       "UI");
        SPEC_HINT_MAP.put("DS",             "DS");           // matches "DS", "Data Science"
        SPEC_HINT_MAP.put("DATA SCIENCE",   "DS");
        SPEC_HINT_MAP.put("CYBER SECURITY", "Cyber Security");
        SPEC_HINT_MAP.put("CYBER",          "Cyber Security");
        SPEC_HINT_MAP.put("SECURITY",       "Cyber Security");
        SPEC_HINT_MAP.put("EC-COUNCIL",     "Cyber Security");
        SPEC_HINT_MAP.put("EC COUNCIL",     "Cyber Security");
        SPEC_HINT_MAP.put("ROBOTICS",       "Robotics");
        // BCA
        SPEC_HINT_MAP.put("SP AI & DS",     "AI");           // BCA AI & Data Science
        SPEC_HINT_MAP.put("AI & DS",        "AI");
        SPEC_HINT_MAP.put("ARTIFICIAL INTELLIGENCE AND DATA SCIENCE", "AI");
        SPEC_HINT_MAP.put("SP AI",          "AI");
        // MCA
        SPEC_HINT_MAP.put("AI & ML MICROSOFT", "AI ML");    // MCA AI ML
        SPEC_HINT_MAP.put("AI & ML MICROSOFT CERTIFICATIONS", "AI ML");
        SPEC_HINT_MAP.put("AI & ML CERT",   "AI ML");
        SPEC_HINT_MAP.put("MICROSOFT",      "AI ML");
        // BSc
        SPEC_HINT_MAP.put("H CS",           "Computer Science");
        SPEC_HINT_MAP.put("H COMPUTER SCIENCE", "Computer Science");
        SPEC_HINT_MAP.put("COMPUTER SCIENCE","Computer Science");
        SPEC_HINT_MAP.put("H DS",           "Data Science");
        SPEC_HINT_MAP.put("H DATA SCIENCE", "Data Science");
        SPEC_HINT_MAP.put("H CYBER",        "Cyber Security");
        SPEC_HINT_MAP.put("H CYBER SECURITY","Cyber Security");
        SPEC_HINT_MAP.put("IBM",            "IBM");
        SPEC_HINT_MAP.put("H IBM",          "IBM");
    }

    private final ProgramRepository          programRepository;
    private final BatchRepository            batchRepository;
    private final CourseRepository           courseRepository;
    private final StudentRepository          studentRepository;
    private final StudentMarkRepository      studentMarkRepository;
    private final SpecializationRepository   specializationRepository;
    private final CORepository               coRepository;
    private final QuestionCOMappingRepository qcoMappingRepository;
    private final AttainmentService          attainmentService;

    // =========================================================================
    //  MAIN ENTRY
    // =========================================================================
    @Transactional
    public Map<String, Object> ingest(MultipartFile file) throws Exception {
        int rowsRead = 0, rowsSaved = 0, rowsSkipped = 0, rowsDuplicate = 0;
        List<String> errors = new ArrayList<>();
        String resolvedSheetName = "(unknown)";

        // Caches
        Map<String, Course>        courseCache  = new HashMap<>();
        Map<String, Student>       studentCache = new HashMap<>();
        Map<String, Program>       programCache = new HashMap<>();
        Map<String, Specialization> specCache   = new HashMap<>();

        // Per-course dedup: "courseId_examType" → Set<"studentId|qLabel|examType">
        Map<String, Set<String>> existingKeysPerCourse = new HashMap<>();

        // Courses touched
        Map<String, Long> processedCourses = new LinkedHashMap<>();

        // Write buffer
        List<StudentMark> writeBuffer = new ArrayList<>();

        // Q column index/label pairs (from header)
        List<Integer> qColIndices = new ArrayList<>();
        List<String>  qLabels     = new ArrayList<>();

        // Track end-term question labels per course (for Q-CO mapping creation)
        Map<Long, Set<String>> courseEndTermQs = new HashMap<>();

        try (InputStream is = file.getInputStream();
             Workbook wb  = new XSSFWorkbook(is)) {

            Sheet sheet = wb.getSheet("Sheet2");
            if (sheet == null) sheet = wb.getSheetAt(0);
            if (sheet == null) throw new IllegalStateException("No sheet found in workbook");
            resolvedSheetName = wb.getSheetName(wb.getSheetIndex(sheet));

            System.out.println("[QWiseReport] sheet='" + resolvedSheetName
                               + "' totalRows=" + sheet.getLastRowNum());

            // ── Parse Q column positions from header row ───────────────────────
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw new IllegalStateException("Header row is missing");

            for (int ci = COL_Q_START; ci < headerRow.getLastCellNum(); ci++) {
                String h = str(headerRow.getCell(ci)).trim();
                if (h.matches("Q\\d+.*") || h.equals("Q")) {
                    qColIndices.add(ci);
                    qLabels.add(h.isEmpty() || h.equals("Q") ? "Q_extra" : h);
                }
            }
            System.out.println("[QWiseReport] Q columns: " + qLabels);

            // ── Process data rows ─────────────────────────────────────────────
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String componentRaw = str(row.getCell(COL_COMPONENT)).trim();
                String componentLow = componentRaw.toLowerCase();

                // Count non-null Q values for this row (needed for class-test rule)
                int nonNullQCount = 0;
                for (int ci : qColIndices) {
                    if (numericOrNull(row.getCell(ci)) != null) nonNullQCount++;
                }

                // ── Exam type detection ───────────────────────────────────────
                String examType;
                boolean isMidTerm = false;

                if (componentRaw.isEmpty()) {
                    // Blank component (UIUX, FSD, BSc DS, BCA, MCA data export gap):
                    // Infer from Q count:
                    //   0 Q values → skip
                    //   1-3 Q values → end_term (individual questions)
                    //   4+ Q values → mid_term (class test spread across 4 questions)
                    if (nonNullQCount == 0) { rowsSkipped++; continue; }
                    if (nonNullQCount >= 4) {
                        examType  = "mid_term";
                        isMidTerm = true;
                    } else {
                        examType = "end_term";
                    }
                } else if (componentLow.contains("class test")) {
                    // User rule: Class Test with marks spread across >=4 questions = mid_term
                    // Class Test with <4 Q columns (e.g., class participation) = skip
                    int freq = (int) numericOrDefault(row.getCell(COL_FREQ_NO), 1);
                    if (freq != 1) { rowsSkipped++; continue; }  // skip Freq 2
                    if (nonNullQCount < 4) { rowsSkipped++; continue; } // class participation, not a real test
                    examType  = "mid_term";
                    isMidTerm = true;
                } else if (componentLow.contains("mid term") || componentLow.contains("midterm")
                        || componentLow.contains("mid-term")) {
                    int freq = (int) numericOrDefault(row.getCell(COL_FREQ_NO), 1);
                    if (freq != 1) { rowsSkipped++; continue; }  // skip Freq 2
                    examType  = "mid_term";
                    isMidTerm = true;
                } else if (componentLow.contains("end term") || componentLow.contains("end-term")
                        || componentLow.contains("end term examination")) {
                    examType = "end_term";
                } else {
                    rowsSkipped++;
                    continue;  // Practical, Attendance, etc.
                }

                rowsRead++;

                String rollNo     = str(row.getCell(COL_ROLL_NO)).trim();
                String name       = str(row.getCell(COL_NAME)).trim();
                String courseCode = str(row.getCell(COL_COURSE_CODE)).trim().toUpperCase();
                String className  = str(row.getCell(COL_CLASS_NAME)).trim();
                String semName    = str(row.getCell(COL_SEM_NAME)).trim();

                if (rollNo.isEmpty() || courseCode.isEmpty() || className.isEmpty()) continue;

                // ── Parse class name → program + batch + specHint ─────────────
                ParsedClassInfo info = parseClassName(className);
                if (info == null) { rowsSkipped++; continue; }

                // ── Resolve Program ───────────────────────────────────────────
                Program program = programCache.computeIfAbsent(info.programKey,
                    k -> programRepository.findByName(k).orElse(null));
                if (program == null) { rowsSkipped++; continue; }

                // ── Resolve Specialization (from class name hint) ─────────────
                String specCacheKey = info.programKey + "_" + (info.specHint != null ? info.specHint : "NONE");
                Specialization spec = specCache.computeIfAbsent(specCacheKey,
                    k -> resolveSpecialization(program, info.specHint));

                // ── Resolve Course (spec-aware, then fallback) ─────────────────
                String courseKey = courseCode + "_" + info.programKey + "_" + info.startYear
                                   + (spec != null ? "_sp" + spec.getId() : "");
                final Program      fp   = program;
                final int          fSY  = info.startYear;
                final int          fEY  = info.endYear;
                final Specialization fSp = spec;

                Course course = courseCache.computeIfAbsent(courseKey, k -> {
                    List<Course> all = courseRepository.findByProgram(fp);
                    // 1. Exact: code + startYear + endYear + specialization
                    if (fSp != null) {
                        Course found = all.stream()
                            .filter(c -> courseCode.equalsIgnoreCase(c.getCourseCode()))
                            .filter(c -> c.getBatch() != null
                                         && c.getBatch().getStartYear() != null
                                         && c.getBatch().getStartYear() == fSY)
                            .filter(c -> fSp.equals(c.getSpecialization()))
                            .findFirst().orElse(null);
                        if (found != null) return found;
                    }
                    // 2. code + startYear (any spec)
                    Course found = all.stream()
                        .filter(c -> courseCode.equalsIgnoreCase(c.getCourseCode()))
                        .filter(c -> c.getBatch() != null
                                     && c.getBatch().getStartYear() != null
                                     && c.getBatch().getStartYear() == fSY)
                        .findFirst().orElse(null);
                    if (found != null) {
                        System.out.println("  [FALLBACK-NO-SPEC] " + courseCode
                                           + " matched without specialization");
                        return found;
                    }
                    // 3. code + any batch
                    found = all.stream()
                        .filter(c -> courseCode.equalsIgnoreCase(c.getCourseCode()))
                        .findFirst().orElse(null);
                    if (found != null) {
                        System.out.println("  [FALLBACK-ANY-BATCH] " + courseCode
                                           + " matched without batch year");
                        return found;
                    }
                    System.out.println("  ✗ [SKIP] Course not found: " + courseCode
                                       + " prog=" + fp.getName());
                    return null;
                });

                if (course == null) { rowsSkipped++; continue; }

                // ── Lazy-load dedup keys for this course+examType ─────────────
                String dedupBucket = course.getId() + "_" + examType;
                if (!existingKeysPerCourse.containsKey(dedupBucket)) {
                    Set<String> keys = new HashSet<>();
                    studentMarkRepository.findByCourseIdWithStudent(course.getId()).forEach(sm -> {
                        if (sm.getStudent() != null && sm.getQuestion() != null
                                && sm.getExamType() != null
                                && examType.equals(sm.getExamType())) {
                            keys.add(sm.getStudent().getId() + "|" + sm.getQuestion() + "|" + sm.getExamType());
                        }
                    });
                    existingKeysPerCourse.put(dedupBucket, keys);
                }
                Set<String> existingKeys = existingKeysPerCourse.get(dedupBucket);

                // ── Resolve / create Student ──────────────────────────────────
                final String fRoll   = rollNo;
                final String fName   = name;
                final Course fCourse = course;
                Student student = studentCache.computeIfAbsent(rollNo, id -> {
                    Student ex = studentRepository.findByEnrollmentNumber(id).orElse(null);
                    if (ex != null) return ex;
                    Student s = new Student();
                    s.setEnrollmentNumber(fRoll);
                    s.setName(fName);
                    s.setProgram(fp);
                    if (fCourse.getBatch() != null) s.setBatch(fCourse.getBatch());
                    if (fSp != null) s.setSpecialization(fSp);
                    return studentRepository.save(s);
                });

                // ── Build marks ───────────────────────────────────────────────
                if (isMidTerm) {
                    // ---- MID-TERM: store as individual M1, M2, M3, M4 marks -----
                    // Rule: Q1→CO1, Q2→CO2, Q3→CO1, Q4→CO2 (cyclic across 2 COs)
                    // Collect non-null Q values
                    List<double[]> midQVals = new ArrayList<>();  // [value, colIdx]
                    for (int qi = 0; qi < qColIndices.size(); qi++) {
                        Double v = numericOrNull(row.getCell(qColIndices.get(qi)));
                        if (v != null) midQVals.add(new double[]{v, qi});
                    }
                    if (midQVals.isEmpty()) { rowsSkipped++; continue; }

                    // Check if student already has ANY mid_term mark for this course (dedup)
                    String midDedupCheck = student.getId() + "|M1|mid_term";
                    boolean alreadyHasMid = existingKeys.contains(midDedupCheck)
                        || existingKeys.contains(student.getId() + "|MID|mid_term");
                    if (alreadyHasMid) { rowsDuplicate++; continue; }

                    double perQMax = MAX_MID_TERM / midQVals.size();
                    int savedMid = 0;
                    for (int mi = 0; mi < midQVals.size(); mi++) {
                        String mLabel = "M" + (mi + 1);  // M1, M2, M3, M4...
                        String dupKey = student.getId() + "|" + mLabel + "|mid_term";
                        if (existingKeys.contains(dupKey)) continue;

                        StudentMark sm = new StudentMark();
                        sm.setStudent(student);
                        sm.setCourse(course);
                        sm.setQuestion(mLabel);
                        sm.setMarks(midQVals.get(mi)[0]);
                        sm.setMaxMarks(Math.round(perQMax * 100.0) / 100.0);
                        sm.setEventMaxMarks(MAX_MID_TERM);
                        sm.setExamType("mid_term");
                        sm.setPeriod(semName);
                        sm.setEventName(componentRaw.length() > 100
                                        ? componentRaw.substring(0, 100) : componentRaw);
                        writeBuffer.add(sm);
                        existingKeys.add(dupKey);
                        savedMid++;
                    }
                    if (savedMid == 0) rowsSkipped++;  // row had no usable Q values

                } else {
                    // ---- END-TERM: individual Q marks -------------------------
                    int activeQ = 0;
                    for (int ci : qColIndices) {
                        if (numericOrNull(row.getCell(ci)) != null) activeQ++;
                    }
                    double perQMax = activeQ > 0 ? MAX_END_TERM / activeQ : MAX_END_TERM;

                    for (int qi = 0; qi < qColIndices.size(); qi++) {
                        int    ci    = qColIndices.get(qi);
                        String label = qLabels.get(qi);
                        Double marks = numericOrNull(row.getCell(ci));
                        if (marks == null) continue;

                        String dupKey = student.getId() + "|" + label + "|end_term";
                        if (existingKeys.contains(dupKey)) { rowsDuplicate++; continue; }

                        StudentMark sm = new StudentMark();
                        sm.setStudent(student);
                        sm.setCourse(course);
                        sm.setQuestion(label);
                        sm.setMarks(Math.min(marks, perQMax));
                        sm.setMaxMarks(perQMax);
                        sm.setEventMaxMarks(MAX_END_TERM);
                        sm.setExamType("end_term");
                        sm.setPeriod(semName);
                        sm.setEventName(componentRaw.length() > 100
                                        ? componentRaw.substring(0, 100) : componentRaw);
                        writeBuffer.add(sm);
                        existingKeys.add(dupKey);

                        // Track Q labels for Q-CO mapping creation
                        courseEndTermQs.computeIfAbsent(course.getId(), x -> new LinkedHashSet<>()).add(label);
                    }
                }

                processedCourses.put(courseKey, course.getId());

                if (writeBuffer.size() >= 500) {
                    studentMarkRepository.saveAll(writeBuffer);
                    rowsSaved += writeBuffer.size();
                    writeBuffer.clear();
                }
            }

            // Final flush
            if (!writeBuffer.isEmpty()) {
                studentMarkRepository.saveAll(writeBuffer);
                rowsSaved += writeBuffer.size();
            }
        }

        System.out.println("[QWiseReport] Done — saved=" + rowsSaved
                           + " skipped=" + rowsSkipped
                           + " duplicates=" + rowsDuplicate
                           + " courses=" + processedCourses.size());

        // ── Auto-create Q-CO mappings for courses that have none ──────────────
        int qcoCreated = 0;
        for (Long courseId : new HashSet<>(processedCourses.values())) {
            try {
                List<QuestionCOMapping> existing = qcoMappingRepository.findByCourseId(courseId);
                // Only auto-create if NO mappings at all for this course
                if (!existing.isEmpty()) continue;

                List<CO> cos = coRepository.findByCourseId(courseId);
                if (cos.isEmpty()) continue;

                Course course = courseRepository.findById(courseId).orElse(null);
                if (course == null) continue;

                // ── Mid-term auto-mapping: M1→CO1, M2→CO2, M3→CO1, M4→CO2 (cyclic over first 2 COs) ──
                // Determine how many M labels were saved for this course
                List<StudentMark> midMarks = studentMarkRepository.findByCourseIdWithStudent(courseId)
                    .stream().filter(sm -> "mid_term".equals(sm.getExamType())).collect(java.util.stream.Collectors.toList());
                Set<String> midLabels = new LinkedHashSet<>();
                midMarks.forEach(sm -> { if (sm.getQuestion() != null && sm.getQuestion().startsWith("M")) midLabels.add(sm.getQuestion()); });

                // Sort M labels (M1, M2, M3, M4)
                List<String> sortedMidLabels = new ArrayList<>(midLabels);
                sortedMidLabels.sort((a, b) -> {
                    try { return Integer.compare(Integer.parseInt(a.substring(1)), Integer.parseInt(b.substring(1))); }
                    catch (Exception e) { return a.compareTo(b); }
                });

                // Compute per-Q max for mid-term
                double midPerQMax = sortedMidLabels.isEmpty() ? MAX_MID_TERM / 4.0 : MAX_MID_TERM / sortedMidLabels.size();
                midPerQMax = Math.round(midPerQMax * 100.0) / 100.0;

                // Map cyclically to first 2 COs: M1→CO[0], M2→CO[1], M3→CO[0], M4→CO[1], ...
                CO co1 = cos.get(0);
                CO co2 = cos.size() > 1 ? cos.get(1) : cos.get(0);
                for (int mi = 0; mi < sortedMidLabels.size(); mi++) {
                    CO targetCo = (mi % 2 == 0) ? co1 : co2;  // M1→CO1, M2→CO2, M3→CO1, M4→CO2
                    QuestionCOMapping midMap = new QuestionCOMapping();
                    midMap.setQuestionLabel(sortedMidLabels.get(mi));
                    midMap.setMaxMarks(midPerQMax);
                    midMap.setCo(targetCo);
                    midMap.setCourse(course);
                    qcoMappingRepository.save(midMap);
                    qcoCreated++;
                }

                // ── End-term auto-mapping: Q labels distributed across remaining COs ──
                Set<String> endQs = courseEndTermQs.getOrDefault(courseId, new LinkedHashSet<>());
                double perQ = endQs.isEmpty() ? 10.0 : MAX_END_TERM / endQs.size();
                // Start CO index from 2 (after the 2 used by mid-term), or from 0 if no mid-term
                int coStartIdx = sortedMidLabels.isEmpty() ? 0 : 2;
                int coIdx = 0;
                for (String qLabel : endQs) {
                    int ciAbsolute = coStartIdx + coIdx;
                    CO co = ciAbsolute < cos.size() ? cos.get(ciAbsolute) : cos.get(cos.size() - 1);
                    QuestionCOMapping qMap = new QuestionCOMapping();
                    qMap.setQuestionLabel(qLabel);
                    qMap.setMaxMarks(Math.round(perQ * 100.0) / 100.0);
                    qMap.setCo(co);
                    qMap.setCourse(course);
                    qcoMappingRepository.save(qMap);
                    qcoCreated++;
                    coIdx++;
                }
            } catch (Exception ex) {
                System.err.println("[QWiseReport] Q-CO auto-create failed for course " + courseId + ": " + ex.getMessage());
            }
        }
        System.out.println("[QWiseReport] Q-CO mappings auto-created: " + qcoCreated);

        // ── Attainment recalculation ──────────────────────────────────────────
        Map<String, Object> attainment = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : processedCourses.entrySet()) {
            try {
                Course c = courseRepository.findById(e.getValue()).orElse(null);
                String key = c != null ? c.getCourseCode() : "Course_" + e.getValue();
                attainment.put(key, attainmentService.getAttainmentReport(e.getValue()));
            } catch (Exception ex) {
                System.err.println("Attainment failed for " + e.getKey() + ": " + ex.getMessage());
            }
        }

        // ── Build response ────────────────────────────────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status",              "SUCCESS");
        result.put("message",             "Question-wise marks report processed successfully");
        result.put("sheet_processed",     resolvedSheetName);
        result.put("rows_read",           rowsRead);
        result.put("mark_records_saved",  rowsSaved);
        result.put("rows_skipped",        rowsSkipped);
        result.put("duplicates_skipped",  rowsDuplicate);
        result.put("courses_processed",   processedCourses.size());
        result.put("qco_mappings_created", qcoCreated);
        result.put("mid_term_max_total",  MAX_MID_TERM);
        result.put("end_term_max_total",  MAX_END_TERM);
        result.put("note",
            "Class Test Freq 1 = mid_term stored as single 'MID' label (sum of all Q values). " +
            "End Term = individual Q marks. Practical/Attendance/Freq 2 skipped.");
        if (!attainment.isEmpty()) result.put("attainment_summary", attainment);
        return result;
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private static class ParsedClassInfo {
        final String programKey;
        final int    startYear;
        final int    endYear;
        final String specHint;    // e.g. "AI & ML", "Full Stack", null for plain CSE

        ParsedClassInfo(String p, int sy, int ey, String sh) {
            programKey = p; startYear = sy; endYear = ey; specHint = sh;
        }
    }

    /**
     * Parses class names like:
     *   "B.Tech CSE (AI & ML)_2025-2029_1_Sem 1"  → BTech, 2025, 2029, "AI & ML"
     *   "B.Tech CSE_2025-2029_1_Sem 1"             → BTech, 2025, 2029, null (CSE)
     *   "BCA (SP AI & DS)_2025-2028_1_Sem 1"       → BCA, 2025, 2028, "SP AI & DS"
     *   "MCA (AI & ML) Microsoft Cert_2025-2027_…" → MCA, 2025, 2027, "AI & ML Microsoft"
     *   "MCA_2025-2027_1_Sem 1"                    → MCA, 2025, 2027, null
     *   "B.Sc. (H) CS_2025-2027_1_Sem 1"           → BSc, 2025, 2027, "H CS"
     */
    private ParsedClassInfo parseClassName(String className) {
        if (className == null || className.isBlank()) return null;
        String[] parts = className.split("_");
        if (parts.length < 2) return null;

        String programPart = parts[0].trim();
        String programKey  = normalizeProgram(programPart);
        if (programKey == null) return null;

        // Extract spec hint from parentheses in programPart, e.g. "(AI & ML)"
        String specHint = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\(([^)]+)\\)").matcher(programPart);
        List<String> hints = new ArrayList<>();
        while (m.find()) hints.add(m.group(1).trim());
        if (!hints.isEmpty()) {
            // Join all parenthetical groups (e.g. "(H) (CS)" → "H CS")
            specHint = String.join(" ", hints).trim();
            if (specHint.isBlank()) specHint = null;
        }

        // Parse batch range from second token: "2025-2029"
        int startYear = 2025, endYear = 0;
        if (parts.length > 1) {
            String batchPart = parts[1].trim();
            String[] years = batchPart.split("-");
            if (years.length >= 1) {
                try { startYear = Integer.parseInt(years[0].trim()); } catch (NumberFormatException e) {}
            }
            if (years.length >= 2) {
                try { endYear = Integer.parseInt(years[1].trim()); } catch (NumberFormatException e) {}
            }
        }
        if (endYear == 0) endYear = startYear + 3;

        return new ParsedClassInfo(programKey, startYear, endYear, specHint);
    }

    /**
     * Resolve the Specialization DB entity that best matches the spec hint from the class name.
     * Strategy: fuzzy match specHint against SPEC_HINT_MAP, then find specialization by program + name fragment.
     */
    private Specialization resolveSpecialization(Program program, String specHint) {
        if (specHint == null || specHint.isBlank()) return null;
        String hintUpper = specHint.toUpperCase().trim();

        // Find the best SPEC_HINT_MAP entry that is a substring of or matches the hint
        String targetFragment = null;
        int bestMatch = 0;
        for (Map.Entry<String, String> e : SPEC_HINT_MAP.entrySet()) {
            if (hintUpper.contains(e.getKey()) || e.getKey().contains(hintUpper)) {
                if (e.getKey().length() > bestMatch) {
                    bestMatch = e.getKey().length();
                    targetFragment = e.getValue();
                }
            }
        }
        if (targetFragment == null) {
            // Direct first-word match attempt
            targetFragment = hints2Fragment(hintUpper);
        }
        if (targetFragment == null) return null;

        final String frag = targetFragment.toUpperCase();
        List<Specialization> specs = specializationRepository.findByProgram(program);
        return specs.stream()
            .filter(s -> s.getName() != null && s.getName().toUpperCase().contains(frag))
            .findFirst().orElse(null);
    }

    /** Fallback: try the first meaningful word of the hint as a fragment. */
    private String hints2Fragment(String hintUpper) {
        String[] words = hintUpper.split("\\s+");
        for (String w : words) {
            if (w.length() >= 2 && !w.equals("SP") && !w.equals("H")
                    && !w.equals("WITH") && !w.equals("AND") && !w.equals("OR")) {
                return w;
            }
        }
        return null;
    }

    private String normalizeProgram(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String up = raw.toUpperCase().replaceAll("[\\s.]+", ""); // strip spaces and dots
        if (up.contains("BTECH") || up.contains("BE")) return "BTech";
        if (up.contains("BSC"))                        return "BSc";
        if (up.contains("BCA"))                        return "BCA";
        if (up.contains("MTECH"))                      return "MTech";
        if (up.contains("MCA"))                        return "MCA";
        return null;
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
                catch (Exception e) { try { yield cell.getStringCellValue(); } catch (Exception e2) { yield ""; } }
            }
            default -> "";
        };
    }

    private Double numericOrNull(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
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

    private double numericOrDefault(Cell cell, double def) {
        Double v = numericOrNull(cell);
        return v != null ? v : def;
    }
}

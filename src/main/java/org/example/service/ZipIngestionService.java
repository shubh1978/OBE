package org.example.service;

import lombok.RequiredArgsConstructor;
import org.example.entity.*;
import org.example.repository.*;
import org.example.util.EnrollmentCodeUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;

@Service
@RequiredArgsConstructor
public class ZipIngestionService {

    // ── Fixed column positions (0-based) — confirmed from actual Excel files ──
    private static final int COL_EVENT_MAX_MARKS = 7;   // total paper max e.g. 20.0
    private static final int COL_ABSENT          = 10;  // non-null/non-empty = absent
    private static final int COL_STUDENT_ID      = 14;  // "2401830001"
    private static final int COL_STUDENT_NAME    = 15;  // "ADITYA CHOUHAN"
    private static final int COL_PROGRAM         = 19;  // "B.Sc. (H) Cyber Security"
    private static final int COL_BATCH           = 20;  // "2024-2027"
    private static final int COL_PERIOD          = 21;  // "Semester-I"
    private static final int COL_COURSE_VARIANT  = 22;  // "ENBC101/COURSE NAME/..."
    private static final int COL_EVENT_NAME      = 24;  // "End Term Examinations..."
    private static final int COL_QUESTIONS_START = 25;  // first Q column

    private final ProgramRepository       programRepository;
    private final SpecializationRepository specializationRepository;
    private final BatchRepository         batchRepository;
    private final SemesterRepository      semesterRepository;
    private final CourseRepository        courseRepository;
    private final StudentRepository       studentRepository;
    private final StudentMarkRepository   studentMarkRepository;
    private final AttainmentService       attainmentService;
    private final QuestionCOMappingRepository questionCOMappingRepository;
    private final CORepository coRepository;
    private final CourseService           courseService;
    private final EnrollmentCodeUtil      enrollmentCodeUtil;

    // ─────────────────────────────────────────────────────────────────────────
    //  MAIN ENTRY — process uploaded ZIP containing multiple .xlsx files
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> ingestZip(MultipartFile zipFile) throws Exception {
        ZipSecureFile.setMinInflateRatio(0.001);

        File tempZip = File.createTempFile("marks_", ".zip");
        zipFile.transferTo(tempZip);

        int filesProcessed = 0, filesSkipped = 0, totalRecords = 0;
        List<String> errors = new ArrayList<>();
        Map<String, Long> processedCourses = new LinkedHashMap<>();  // courseCode_batchKey -> courseId

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(tempZip)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".xlsx")) continue;

                String filename = entry.getName().substring(entry.getName().lastIndexOf("/") + 1);
                System.out.println("Processing: " + filename);

                try (InputStream is = zip.getInputStream(entry)) {
                    Map<String, Object> fileResult = processExcelFileV2(is, filename);
                    int count = (Integer) fileResult.get("records");
                    Long courseId = (Long) fileResult.get("courseId");
                    String courseKey = (String) fileResult.get("courseKey");
                    
                    totalRecords += count;
                    filesProcessed++;
                    
                    if (courseId != null && courseKey != null) {
                        processedCourses.put(courseKey, courseId);
                    }
                    
                    System.out.println("  → Saved " + count + " mark records for course " + courseKey);
                } catch (Exception e) {
                    String msg = "FAILED [" + filename + "]: " + e.getMessage();
                    System.err.println(msg);
                    errors.add(msg);
                    filesSkipped++;
                }
            }
        } finally {
            tempZip.delete();
        }

        // Calculate attainments for processed courses
        Map<String, Object> attainmentSummary = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : processedCourses.entrySet()) {
            Long courseId = entry.getValue();
            try {
                Map<String, Object> courseAttainment = attainmentService.getAttainmentReport(courseId);
                Course course = courseRepository.findById(courseId).orElse(null);
                String courseKey = course != null ? course.getCourseCode() : "Course_" + courseId;
                attainmentSummary.put(courseKey, courseAttainment);
            } catch (Exception e) {
                System.err.println("Failed to calculate attainment for course " + courseId + ": " + e.getMessage());
            }
        }

        // Build final response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Marks uploaded successfully");
        result.put("status", filesSkipped == 0 ? "SUCCESS" : "PARTIAL");
        result.put("files_processed", filesProcessed);
        result.put("files_skipped", filesSkipped);
        result.put("total_mark_records_saved", totalRecords);
        
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        
        if (!attainmentSummary.isEmpty()) {
            result.put("attainment_summary", attainmentSummary);
        }
        
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SINGLE EXCEL ENTRY — upload one question-wise marks report directly
    //  (same logic as ZIP ingestion but for a single .xlsx file)
    //
    //  ✅ LOCAL  : hits localhost:8080
    //  🚀 PROD   : no change needed
    //
    //  Exam type is inferred from the uploaded filename:
    //    "(mid term)" or "mid_term" in the name  → mid_term
    //    "(end term)" or "end_term" in the name  → end_term
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> ingestSingleExcel(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) filename = "upload.xlsx";

        System.out.println("[SingleExcel] Processing file: " + filename);

        Map<String, Object> fileResult;
        try (InputStream is = file.getInputStream()) {
            fileResult = processExcelFileV2(is, filename);
        }

        int records   = (Integer) fileResult.getOrDefault("records", 0);
        Long courseId = (Long)    fileResult.get("courseId");
        String courseKey = (String) fileResult.get("courseKey");

        // Recalculate attainment for the affected course
        Map<String, Object> attainmentResult = new LinkedHashMap<>();
        if (courseId != null) {
            try {
                attainmentResult = attainmentService.getAttainmentReport(courseId);
            } catch (Exception e) {
                System.err.println("[SingleExcel] Attainment calc failed: " + e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status",              "SUCCESS");
        result.put("message",             "Single mark sheet processed successfully");
        result.put("file",                filename);
        result.put("course",              courseKey != null ? courseKey : "(unknown)");
        result.put("mark_records_saved",  records);
        if (!attainmentResult.isEmpty()) result.put("attainment_summary", attainmentResult);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PROCESS ONE EXCEL FILE - Returns course ID and key for attainment calculation
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> processExcelFile(InputStream is, String filename) throws Exception {
        try (Workbook wb = new XSSFWorkbook(is)) {

            // ── Auto-detect the marks sheet ───────────────────────────────────
            // Try "EntrySheet" first (standard format), then scan all sheets for
            // one whose header contains a student-ID column (marks entry reports).
            Sheet sheet = wb.getSheet("EntrySheet");

            if (sheet == null) {
                // Scan all sheets — look for marks-report columns in header row
                for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                    Sheet candidate = wb.getSheetAt(si);
                    Row hdr = candidate.getRow(0);
                    if (hdr == null) continue;
                    for (int ci = 0; ci < Math.min(hdr.getLastCellNum(), 30); ci++) {
                        String h = hdr.getCell(ci) != null
                                   ? hdr.getCell(ci).toString().trim().toLowerCase() : "";
                        // "Student Id", "STUDENT_ID_T", "Student Name" are unique to marks sheets
                        if (h.contains("student id") || h.contains("student_id")
                                || h.contains("student name")) {
                            sheet = candidate;
                            System.out.println("[ZipIngestion] Using sheet '"
                                               + wb.getSheetName(si) + "' (auto-detected)");
                            break;
                        }
                    }
                    if (sheet != null) break;
                }
            }

            // Last resort: first sheet
            if (sheet == null && wb.getNumberOfSheets() > 0) {
                sheet = wb.getSheetAt(0);
                System.out.println("[ZipIngestion] ⚠ No marks sheet detected in '"
                                   + filename + "', falling back to first sheet '"
                                   + wb.getSheetName(0) + "'");
            }

            if (sheet == null) throw new IllegalStateException(
                "No sheets found in file: " + filename);

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw new IllegalStateException("Header row missing");

            // Detect exam type from filename — handles both:
            //   "end_term" (underscore format from old ZIP files)
            //   "(end term)" / "(End Term)" (space format from SOET folder files)
            String filenameLower = filename.toLowerCase();
            String examType = (filenameLower.contains("end_term") || filenameLower.contains("end term"))
                              ? "end_term" : "mid_term";


            // ── Parse question columns ────────────────────────────────────────
            // Skip: _ID, MARKS_ID, MAX_MARKS, QUESTION_MARKS_SUM
            // Accept: any column starting with Q followed by a digit
            //   "Q1(a) (5.0)" → label "Q1(a)", max from header or MAX_MARKS col
            //   "Q1(a)"       → label "Q1(a)", max from MAX_MARKS col
            //   "Q1 (10.0)"   → label "Q1",    max from header
            //   "Q1"          → label "Q1",     max from MAX_MARKS col

            Map<String, Integer> labelToMarksCol = new LinkedHashMap<>();
            Map<String, Double>  labelToMax      = new HashMap<>();

            // IMPROVED: Scan entire header row for question columns (not just from COL_QUESTIONS_START)
            // Build a map of MAX_MARKS column index → question label for later multi-row scan
            Map<Integer, String> maxMarksColToLabel = new HashMap<>();

            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String h = getString(headerRow.getCell(c));
                if (h.isEmpty()) continue;

                // MAX_MARKS columns → record column index, scan for value later (multi-row)
                if (h.endsWith("MAX_MARKS") && !h.equals("EVENT_MAX_MARKS")) {
                    String label = h.replace("MAX_MARKS", "").trim();
                    maxMarksColToLabel.put(c, label);
                    continue;
                }
                // Skip ID and summary columns
                if (h.endsWith("_ID") || h.endsWith("MARKS_ID") ||
                        h.equals("QUESTION_MARKS_SUM") || h.equals("EVENT_MAX_MARKS")) continue;

                boolean isQuestionColumn = false;
                String hUpper = h.toUpperCase().trim();

                // Pattern 1: Standard Q format - Q1, Q2, Q1(a), Q1(b), Q1 (5), etc.
                if (h.matches("^Q\\s*\\d.*")) {
                    isQuestionColumn = true;
                }
                // Pattern 2: Question word format
                else if (hUpper.contains("QUESTION") && hUpper.matches(".*Q\\s*-?\\s*\\d+.*")) {
                    isQuestionColumn = true;
                }
                // Pattern 3: Q with separator (Q-1, Q 1, etc.)
                else if (h.matches("^Q[\\s\\-_]?\\d+.*")) {
                    isQuestionColumn = true;
                }

                if (!isQuestionColumn) continue;

                // Clean label: strip trailing " (5.0)" if present
                String label = h.replaceAll("\\s*\\(\\d+\\.?\\d*\\)\\s*$", "").trim();
                labelToMarksCol.put(label, c);

                // If max is embedded in header "Q1(a) (5.0)" → extract 5.0
                Matcher maxInHeader = Pattern.compile("\\((\\d+\\.?\\d*)\\)\\s*$").matcher(h);
                if (maxInHeader.find() && !labelToMax.containsKey(label)) {
                    labelToMax.put(label, Double.parseDouble(maxInHeader.group(1)));
                }
            }

            // ── Multi-row scan for MAX_MARKS columns ──────────────────────────────
            // Scan up to 20 data rows to find the first non-zero max value for each
            // MAX_MARKS column. This handles files where row 1 has a blank/absent student.
            for (Map.Entry<Integer, String> mEntry : maxMarksColToLabel.entrySet()) {
                int maxCol = mEntry.getKey();
                String label = mEntry.getValue();
                if (labelToMax.containsKey(label)) continue; // already got it from header
                int maxRowsToScan = Math.min(21, sheet.getLastRowNum() + 1);
                for (int r = 1; r < maxRowsToScan; r++) {
                    Row dataRow = sheet.getRow(r);
                    if (dataRow == null) continue;
                    // Skip absent rows
                    if (!getString(dataRow.getCell(COL_ABSENT)).isEmpty()) continue;
                    double maxVal = getNumeric(dataRow.getCell(maxCol));
                    if (maxVal > 0) {
                        labelToMax.put(label, maxVal);
                        break;
                    }
                }
            }

            // ── Fallback: infer per-question max from actual data rows ─────────────
            // For question columns still missing a max, scan the first 20 non-absent rows
            // and take the maximum value seen in that column as the likely max marks.
            // This handles files where max is not stored in a dedicated MAX_MARKS column.
            for (Map.Entry<String, Integer> qEntry : labelToMarksCol.entrySet()) {
                String label = qEntry.getKey();
                if (labelToMax.containsKey(label)) continue;
                int col = qEntry.getValue();
                double inferredMax = 0.0;
                int rowsScanned = 0;
                for (int r = 1; r <= sheet.getLastRowNum() && rowsScanned < 30; r++) {
                    Row dataRow = sheet.getRow(r);
                    if (dataRow == null) continue;
                    if (!getString(dataRow.getCell(COL_ABSENT)).isEmpty()) continue;
                    rowsScanned++;
                    double val = getNumeric(dataRow.getCell(col));
                    if (val > inferredMax) inferredMax = val;
                }
                if (inferredMax > 0) {
                    labelToMax.put(label, inferredMax);
                    System.out.println("  [MAX_INFER] '" + label + "' max inferred from data rows: " + inferredMax);
                }
            }

            if (labelToMarksCol.isEmpty()) {
                // Debug: Log all column headers found with their column indices
                StringBuilder colDebug = new StringBuilder("Available columns from col 0: [");
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    String h = getString(headerRow.getCell(c));
                    if (!h.isEmpty()) {
                        colDebug.append(String.format("(%d:'%s'), ", c, h));
                    }
                }
                colDebug.append("]");
                System.err.println(colDebug.toString());
                
                // Additional debug: show what's at question start column
                System.err.println(String.format("Question columns start at column %d", COL_QUESTIONS_START));
                StringBuilder questionColDebug = new StringBuilder("Question range (col ").append(COL_QUESTIONS_START).append("+): [");
                for (int c = COL_QUESTIONS_START; c < Math.min(COL_QUESTIONS_START + 20, headerRow.getLastCellNum()); c++) {
                    String h = getString(headerRow.getCell(c));
                    if (!h.isEmpty()) {
                        questionColDebug.append(String.format("(%d:'%s'), ", c, h));
                    }
                }
                questionColDebug.append("]");
                System.err.println(questionColDebug.toString());
                
                throw new IllegalStateException("No question mark columns found in: " + filename);
            }

            // ── AUTO-CREATE CO AND QUESTION-CO MAPPINGS ───────────────────────
            // Map: question label → extracted CO number
            // Q1, Q1(a), Q1(b) → CO1  (all sub-parts of Q1 map to CO1)
            // Q2, Q2(a), Q2(b) → CO2, etc.
            // CRITICAL: only the ROOT question number determines the CO, NOT sub-parts.
            // E.g. Q1(a) → root=1 → CO1; Q10 → root=10 → CO10.
            // We cap at the number of DISTINCT root numbers (e.g. Q1,Q2,Q3 → 3 COs).
            Map<String, Integer> questionToCoNumber = new HashMap<>();
            Set<Integer> distinctRootNums = new java.util.TreeSet<>();
            for (String qLabel : labelToMarksCol.keySet()) {
                // Extract the FIRST numeric group — this is the root question number
                // Q1 → 1, Q1(a) → 1, Q2(b) → 2, Q10 → 10
                java.util.regex.Matcher qm = java.util.regex.Pattern.compile("^Q(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(qLabel.trim());
                if (qm.find()) {
                    try {
                        int rootNum = Integer.parseInt(qm.group(1));
                        distinctRootNums.add(rootNum);
                        questionToCoNumber.put(qLabel, rootNum);
                    } catch (NumberFormatException e) {
                        // Skip
                    }
                }
            }
            // Re-number COs sequentially: e.g. if roots are {1,2,3} → CO1,CO2,CO3
            // If roots are {1,3,5} → CO1,CO2,CO3 (compact sequential mapping)
            Map<Integer, Integer> rootToSeqCo = new java.util.LinkedHashMap<>();
            int seqCounter = 1;
            for (int root : distinctRootNums) {
                rootToSeqCo.put(root, seqCounter++);
            }
            // Replace questionToCoNumber values with sequential CO numbers
            for (String qLabel : new ArrayList<>(questionToCoNumber.keySet())) {
                int root = questionToCoNumber.get(qLabel);
                questionToCoNumber.put(qLabel, rootToSeqCo.getOrDefault(root, root));
            }

            // ─────────────────────────────────────────────────────────────────
            // Caches to avoid repeated DB lookups per file
            // ── Caches to avoid repeated DB lookups per file ──────────────────
            Map<String, Student> studentCache  = new HashMap<>();
            Map<String, Course>  courseCache   = new HashMap<>();
            Map<String, Program> programCache  = new HashMap<>();
            Map<String, Batch>   batchCache    = new HashMap<>();
            Map<String, QuestionCOMapping> mappingCache = new HashMap<>();  // NEW: Cache for mappings

            // ── Existing marks set for deduplication ──────────────────────────
            // Loaded lazily the first time we see the course. Key: studentId + "|" + question + "|" + examType
            Set<String> existingMarkKeys = null;  // null = not yet loaded

            List<StudentMark> batch = new ArrayList<>();
            int saved = 0;
            
            // Track the course being used for this file
            Long processedCourseId = null;
            String processedCourseKey = null;

            // ── Process each student row ──────────────────────────────────────
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                if (!getString(row.getCell(COL_ABSENT)).isEmpty()) continue; // absent

                String enrollmentNo = getString(row.getCell(COL_STUDENT_ID));
                if (enrollmentNo.isEmpty()) continue;

                String studentName  = getString(row.getCell(COL_STUDENT_NAME));
                String programStr   = getString(row.getCell(COL_PROGRAM));   // "B.Sc. (H) Cyber Security"
                String batchStr     = getString(row.getCell(COL_BATCH));     // "2024-2027"
                String period       = getString(row.getCell(COL_PERIOD));    // "Semester-I"
                String courseVariant= getString(row.getCell(COL_COURSE_VARIANT)); // "ENBC101/NAME/..."
                String eventName    = getString(row.getCell(COL_EVENT_NAME));
                double eventMax     = getNumeric(row.getCell(COL_EVENT_MAX_MARKS));

                // ── Resolve Course (strict: must already exist in DB) ────────────
                // Do course resolution FIRST so we can derive the correct batch from it
                String courseCode = courseVariant.contains("/")
                        ? courseVariant.split("/")[0].trim()
                        : filename.split("_")[0];

                int semesterNum = parseSemesterNumber(period);  // "Semester-I" → 1

                // ── Resolve Program ─────────────────────────────────────────────
                String progName = normalizeProgram(programStr);
                Program program = programCache.computeIfAbsent(progName, name ->
                        programRepository.findByName(name).orElse(null));

                if (program == null) {
                    System.out.println("  ✗ [SKIP] Program not found in DB: '" + progName + "' — skipping row " + r);
                    continue;
                }

                // ── Resolve Batch (start_year from batchStr) ────────────────────
                // Strategy: find the course first, then use the course's own batch.
                // This ensures students are linked to the correct specialization batch.
                int startYear = parseBatchStartYear(batchStr);
                int endYear   = parseBatchEndYear(batchStr, startYear);
                final Program fp = program;
                final String finalCourseCode = courseCode.toUpperCase();
                final int finalStartYear = startYear;

                // Step 1: Find the course (by code + program, prioritize start-year match)
                String courseKey = finalCourseCode + "_" + progName + "_" + startYear;
                Course course = courseCache.computeIfAbsent(courseKey, k -> {
                    // Primary: match by course code + program + start year (via batch)
                    List<Course> programCourses = courseRepository.findByProgram(fp);
                    // Prefer course whose batch start year matches
                    Course found = programCourses.stream()
                            .filter(c -> c.getCourseCode() != null && c.getCourseCode().equalsIgnoreCase(finalCourseCode))
                            .filter(c -> c.getBatch() != null && c.getBatch().getStartYear() != null && c.getBatch().getStartYear() == finalStartYear)
                            .findFirst().orElse(null);

                    if (found != null) {
                        System.out.println("  ✓ Matched course: " + found.getId() + " (" + found.getCourseCode() + ") batch_year=" + found.getBatch().getStartYear());
                        return found;
                    }

                    // Fallback: any course with matching code under this program
                    found = programCourses.stream()
                            .filter(c -> c.getCourseCode() != null && c.getCourseCode().equalsIgnoreCase(finalCourseCode))
                            .findFirst().orElse(null);

                    if (found != null) {
                        System.out.println("  ✓ Matched course by program fallback: " + found.getId() + " (" + found.getCourseCode() + ")");
                        return found;
                    }

                    // NOT FOUND — do NOT auto-create; return null to skip
                    System.out.println("  ✗ [REJECT] Course '" + finalCourseCode + "' not found in DB for program '" + fp.getName() + "'. " +
                            "Upload the structure Excel first. Skipping marks for this course.");
                    return null;
                });

                if (course == null) {
                    // Course not in DB — skip all rows for this course variant
                    continue;
                }

                // Step 2: Derive the batch from the course itself
                // This ensures students are linked to the correct specialization batch.
                Batch batchObj = course.getBatch();
                if (batchObj == null) {
                    // Fallback: look up using null-spec batch
                    String batchKey = progName + "_" + startYear;
                    batchObj = batchCache.computeIfAbsent(batchKey, k ->
                            batchRepository.findByStartYearAndProgramAndSpecialization(startYear, program, null)
                                    .orElse(null));
                }

                if (batchObj == null) {
                    System.out.println("  ✗ [SKIP] Batch not found for course " + courseCode + " — skipping row " + r);
                    continue;
                }

                // ── Resolve Semester ────────────────────────────────────────────
                // Use the course's own semester directly (already correct)
                Semester semester = course.getSemester();
                if (semester == null) {
                    // Fallback: look up by number + batch
                    final int semNum2 = semesterNum;
                    final Batch fb2 = batchObj;
                    semester = semesterRepository.findByNumberAndBatch(semNum2, fb2).orElse(null);
                }

                if (semester == null) {
                    System.out.println("  ✗ [SKIP] Semester not found for course " + courseCode + " — skipping row " + r);
                    continue;
                }

                // Record the course being used
                if (processedCourseId == null) {
                    processedCourseId = course.getId();
                    processedCourseKey = courseKey;
                    // Load existing marks for this course to prevent duplicates on re-upload
                    existingMarkKeys = new HashSet<>();
                    for (StudentMark existing : studentMarkRepository.findByCourseIdWithStudent(processedCourseId)) {
                        if (existing.getStudent() != null && existing.getQuestion() != null && existing.getExamType() != null) {
                            existingMarkKeys.add(existing.getStudent().getId() + "|" + existing.getQuestion() + "|" + existing.getExamType());
                        }
                    }
                    System.out.println("  [DEDUP] Loaded " + existingMarkKeys.size() + " existing mark keys for course " + processedCourseKey);
                }

                // ── AUTO-CREATE QUESTION-CO MAPPINGS ──────────────────────────
                // For each question, auto-create or update QuestionCOMapping
                final Course finalCourse = course;
                for (Map.Entry<String, Integer> qEntry : questionToCoNumber.entrySet()) {
                    String qLabel = qEntry.getKey();
                    int coNum = qEntry.getValue();
                    double maxMarks = labelToMax.getOrDefault(qLabel, 0.0);
                    
                    if (maxMarks <= 0) continue; // Skip if no max
                    
                    String mappingKey = finalCourse.getId() + "_" + qLabel + "_CO" + coNum;
                    
                    if (!mappingCache.containsKey(mappingKey)) {
                        try {
                            // CO code format: COURSECODE-CON  (matches structure-parser format)
                            // e.g. "ENBC101-CO1"  — NOT just "CO1" which creates orphaned duplicates
                            String shortCode = "CO" + coNum;
                            String fullCode  = finalCourse.getCourseCode() + "-CO" + coNum;
                            // Search: full code first, then short code (backward compat), then create
                            CO co = coRepository.findByCodeAndCourse(fullCode, finalCourse)
                                    .orElseGet(() -> coRepository.findByCodeAndCourse(shortCode, finalCourse)
                                    .orElseGet(() -> {
                                        CO newCO = new CO();
                                        newCO.setCode(fullCode);   // always persist with full code
                                        newCO.setDescription("Auto-created from question mapping");
                                        newCO.setCourse(finalCourse);
                                        return coRepository.save(newCO);
                                    }));
                            
                            // Check if mapping already exists using proper DB query (not loadAll)
                            // This avoids N+1 and unnecessary memory consumption
                            boolean mappingExists = questionCOMappingRepository.findByCourseId(finalCourse.getId()).stream()
                                    .anyMatch(m -> m.getQuestionLabel().equalsIgnoreCase(qLabel) &&
                                                   m.getCo().getId().equals(co.getId()));
                            
                            if (!mappingExists) {
                                QuestionCOMapping mapping = new QuestionCOMapping();
                                mapping.setCourse(finalCourse);
                                mapping.setQuestionLabel(qLabel);
                                mapping.setCo(co);
                                mapping.setMaxMarks(maxMarks);
                                questionCOMappingRepository.save(mapping);
                                mappingCache.put(mappingKey, mapping);
                            } else {
                                // Mapping already exists — just add to cache for this file
                                mappingCache.put(mappingKey, null);
                            }
                        } catch (Exception e) {
                            // Log but don't fail the entire upload
                            System.err.println("Warning: Could not create mapping for " + qLabel + ": " + e.getMessage());
                        }
                    }
                }

                // ── Resolve Student ────────────────────────────────────────────
                final Program fprog = program;
                final Batch fbo = batchObj;

                // Resolve specialization from ENROLLMENT NUMBER (most reliable source).
                // Using the course batch's specialization causes cross-contamination:
                // e.g. a BTech DS student (code "42") whose ENMA101 mark lands in the
                // batch-1 (UI/UX) course entity would be tagged as UI/UX instead of DS.
                // Enrollment code is always programme-aware and unique per specialization.
                Specialization enrollmentSpec = null;
                try {
                    String specCode = enrollmentCodeUtil.extractSpecCode(enrollmentNo);
                    String specName = specCode != null ? enrollmentCodeUtil.getSpecNameForCode(specCode) : null;
                    if (specName != null) {
                        final String fsn = specName;
                        enrollmentSpec = specializationRepository.findByProgram(program).stream()
                                .filter(s -> fsn.equalsIgnoreCase(s.getName())
                                          || s.getName().toLowerCase().contains(fsn.toLowerCase())
                                          || fsn.toLowerCase().contains(s.getName().toLowerCase()))
                                .findFirst().orElse(null);
                    }
                } catch (Exception ignored) {}

                // Fall back to course-batch spec only when enrollment lookup returns nothing
                // (e.g. KR-prefix lateral entry students without a standard code)
                final Specialization finalSpec = (enrollmentSpec != null) ? enrollmentSpec
                        : ((fbo != null) ? fbo.getSpecialization() : null);

                Student student = studentCache.computeIfAbsent(enrollmentNo, id -> {
                    Student existing = studentRepository.findByEnrollmentNumber(id).orElse(null);
                    if (existing != null) {
                        // Update specialization when:
                        // a) not yet set, OR
                        // b) currently set to a wrong-program specialization (cross-program contamination)
                        boolean wrongProgram = existing.getSpecialization() != null
                                && existing.getSpecialization().getProgram() != null
                                && !existing.getSpecialization().getProgram().getId().equals(program.getId());
                        if ((existing.getSpecialization() == null || wrongProgram) && finalSpec != null) {
                            existing.setSpecialization(finalSpec);
                            return studentRepository.save(existing);
                        }
                        return existing;
                    }
                    Student s = new Student();
                    s.setEnrollmentNumber(id);
                    s.setName(studentName);
                    s.setProgram(fprog);
                    s.setBatch(fbo);
                    s.setSpecialization(finalSpec);
                    return studentRepository.save(s);
                });


                // ── Create one StudentMark per question ───────────────────────
                for (Map.Entry<String, Integer> qe : labelToMarksCol.entrySet()) {
                    String qLabel  = qe.getKey();
                    Cell   cell    = row.getCell(qe.getValue());
                    if (cell == null || cell.getCellType() == CellType.BLANK) continue;

                    double marksScored = getNumeric(cell);
                    double maxMarks    = labelToMax.getOrDefault(qLabel, 0.0);
                    if (maxMarks <= 0) continue;  // skip if no max — unusable
                    // Allow negative marks (deductions) — clamp to -maxMarks floor
                    if (marksScored < -maxMarks) marksScored = -maxMarks;

                    // ── Deduplication: skip if this mark already exists in DB ────
                    if (existingMarkKeys != null) {
                        String dupKey = student.getId() + "|" + qLabel + "|" + examType;
                        if (existingMarkKeys.contains(dupKey)) continue;
                        existingMarkKeys.add(dupKey);  // track so batch-within-file doesn't also dup
                    }

                    StudentMark sm = new StudentMark();
                    sm.setStudent(student);
                    sm.setCourse(course);
                    sm.setQuestion(qLabel);
                    sm.setMarks(marksScored);
                    sm.setMaxMarks(maxMarks);
                    sm.setEventMaxMarks(eventMax);
                    sm.setExamType(examType);
                    sm.setPeriod(period);
                    sm.setEventName(eventName);
                    batch.add(sm);
                }

                if (batch.size() >= 500) {
                    studentMarkRepository.saveAll(batch);
                    saved += batch.size();
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                studentMarkRepository.saveAll(batch);
                saved += batch.size();
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("saved", saved);
            result.put("courseId", processedCourseId);
            result.put("courseKey", processedCourseKey);
            return result;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PROCESS EXCEL FILE V2 - Returns course ID and key for attainment calculation
    // ─────────────────────────────────────────────────────────────────────────
    private Map<String, Object> processExcelFileV2(InputStream is, String filename) throws Exception {
        Map<String, Object> fileResult = processExcelFile(is, filename);
        
        Map<String, Object> result = new HashMap<>();
        result.put("records", fileResult.getOrDefault("saved", 0));
        result.put("courseId", fileResult.get("courseId"));
        result.put("courseKey", fileResult.get("courseKey"));
        result.put("filename", filename);
        
        return result;
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private Program newProgram(String name) {
        Program p = new Program(); p.setName(name); return p;
    }

    /**
     * Resolve a Specialization from the full program name string in the marks Excel.
     * E.g. "BCA (H) (Sp AI & DS) (Research)" → BCA specialization "Artificial Intelligence and Data Science"
     *      "B.Tech CSE (AI & ML) Samatrix"    → BTech specialization "Artificial Intelligence and Machine Learning"
     *      "B.Tech CSE"                        → null (no specialization)
     *      "B.Sc. (H) Cyber Security"          → BSc specialization "Cyber Security"
     *
     * Falls back to null (no spec) if no match found — the student can be manually
     * assigned later via the enrolment-Excel upload endpoint.
     */
    private Specialization resolveSpecFromProgramString(String programStr, Program program) {
        if (programStr == null || program == null) return null;
        String lower = programStr.toLowerCase();

        // Ordered from most-specific to least-specific so we don't match "data science"
        // before "artificial intelligence and data science".
        String[][] patterns = {
            {"artificial intelligence and data science",  "Artificial Intelligence and Data Science"},
            {"ai & ds",                                   "Artificial Intelligence and Data Science"},
            {"ai and ds",                                 "Artificial Intelligence and Data Science"},
            {"sp ai",                                     "Artificial Intelligence and Data Science"},
            {"artificial intelligence and machine learning", "Artificial Intelligence and Machine Learning"},
            {"ai & ml",                                   "Artificial Intelligence and Machine Learning"},
            {"ai and ml",                                 "Artificial Intelligence and Machine Learning"},
            {"samatrix",                                  "Artificial Intelligence and Machine Learning"},
            {"sp inv",                                    "Artificial Intelligence and Machine Learning"},
            {"full stack",                                "Full Stack Development"},
            {"xebia",                                     "Full Stack Development"},
            {"cyber security",                            "Cyber Security"},
            {"ec-council",                                "Cyber Security"},
            {"data science",                              "Data Science"},
            {"ibm",                                       "Data Science"},
            {"ux or ui",                                  "UX/UI"},
            {"ux/ui",                                     "UX/UI"},
            {"imaginxp",                                  "UX/UI"},
            {"computer science",                          "Computer Science"},
        };

        for (String[] pair : patterns) {
            if (lower.contains(pair[0])) {
                String specName = pair[1];
                // Look up by name AND program to avoid cross-program matches
                try {
                    return specializationRepository.findByNameAndProgram(specName, program).orElse(
                        // Try case-insensitive partial match
                        specializationRepository.findByProgram(program).stream()
                            .filter(s -> s.getName() != null &&
                                         s.getName().toLowerCase().contains(specName.toLowerCase()))
                            .findFirst().orElse(null)
                    );
                } catch (Exception e) {
                    System.err.println("Warning: spec lookup failed for '" + specName + "': " + e.getMessage());
                }
            }
        }
        return null; // No match — null spec = shared/base program
    }

    /**
     * Derive a Specialization from the 2-digit code at enrollment-number positions 4-5.
     * E.g. "10"/"11"/"12" → AI & ML, "17"/"18" → Full Stack, "40"/"41" → Cyber Security.
     * Codes "01" (plain BTech CSE), "20" (plain BCA), "73" (plain BSc CS) map to null
     * because there is no specific sub-specialization for those students.
     */
    private Specialization resolveSpecFromEnrollmentCode(String code, Program program) {
        if (code == null || program == null) return null;
        Map<String, String> codeToSpec = new java.util.HashMap<>();
        codeToSpec.put("10", "Artificial Intelligence and Machine Learning");
        codeToSpec.put("11", "Artificial Intelligence and Machine Learning");
        codeToSpec.put("12", "Artificial Intelligence and Machine Learning");
        codeToSpec.put("17", "Full Stack Development");
        codeToSpec.put("18", "Full Stack Development");
        codeToSpec.put("19", "Data Science");
        codeToSpec.put("40", "Cyber Security");
        codeToSpec.put("41", "Cyber Security");
        codeToSpec.put("42", "UX/UI");
        codeToSpec.put("21", "Artificial Intelligence and Data Science");
        codeToSpec.put("83", "Cyber Security");
        codeToSpec.put("84", "Data Science");
        // codes "01", "20", "73" = plain program, no sub-specialization
        String specName = codeToSpec.get(code);
        if (specName == null) return null;
        try {
            return specializationRepository.findByNameAndProgram(specName, program)
                .orElseGet(() -> specializationRepository.findByProgram(program).stream()
                    .filter(s -> s.getName() != null &&
                                 s.getName().toLowerCase().contains(specName.toLowerCase()))
                    .findFirst().orElse(null));
        } catch (Exception e) {
            System.err.println("Warning: spec lookup by enroll code '" + code + "' failed: " + e.getMessage());
            return null;
        }
    }

    /** "ENGINEERING CALCULUS" → "Engineering Calculus", "engineering calculus" → "Engineering Calculus" */
    private String normalizeCourseName(String raw) {
        if (raw == null || raw.isBlank()) return "Unknown Course";
        // Convert to title case: split by space/punctuation, capitalize first letter of each word
        String[] words = raw.toLowerCase().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (result.length() > 0) result.append(" ");
            if (word.length() > 0) {
                result.append(word.substring(0, 1).toUpperCase()).append(word.substring(1));
            }
        }
        return result.toString();
    }

    /** "B.Sc. (H) Cyber Security" → "BSc", "BCA" → "BCA" */
    private String normalizeProgram(String raw) {
        if (raw == null || raw.isBlank()) return "Unknown";
        String up = raw.toUpperCase();
        if (up.contains("BTECH") || up.contains("B.TECH") || up.contains("B. TECH")) return "BTech";
        if (up.contains("MCA"))  return "MCA";
        if (up.contains("MTECH") || up.contains("M.TECH")) return "MTech";
        if (up.contains("BCA"))  return "BCA";
        if (up.contains("BSC") || up.contains("B.SC") || up.contains("B. SC")) return "BSc";
        return raw.trim();
    }

    /** "2024-2027" → 2024 */
    private int parseBatchStartYear(String batchStr) {
        if (batchStr == null || batchStr.isBlank()) return 2024;
        Matcher m = Pattern.compile("(\\d{4})").matcher(batchStr);
        return m.find() ? Integer.parseInt(m.group(1)) : 2024;
    }

    /** "2024-2027" → 2027 */
    private int parseBatchEndYear(String batchStr, int defaultStart) {
        if (batchStr == null || batchStr.isBlank()) return defaultStart + 3;
        Matcher m = Pattern.compile("\\d{4}-(\\d{4})").matcher(batchStr);
        return m.find() ? Integer.parseInt(m.group(1)) : defaultStart + 3;
    }

    /** "Semester-I"→1, "Semester-II"→2, "Semester-III"→3, "Semester 3"→3 */
    private int parseSemesterNumber(String period) {
        if (period == null) return 1;
        String p = period.toUpperCase().trim();
        if (p.contains("VIII") || p.contains("8")) return 8;
        if (p.contains("VII")  || p.contains("7")) return 7;
        if (p.contains("VI")   || p.contains("6")) return 6;
        if (p.contains("V")    || p.contains("5")) return 5;
        if (p.contains("IV")   || p.contains("4")) return 4;
        if (p.contains("III")  || p.contains("3")) return 3;
        if (p.contains("II")   || p.contains("2")) return 2;
        return 1;
    }

    /** "ENBC101/FUNDAMENTALS OF WEB TECHNOLOGIES/Odd Semester 2024-2025/Group 1" → "FUNDAMENTALS OF WEB TECHNOLOGIES" */
    private String extractCourseName(String courseVariant) {
        if (courseVariant == null || !courseVariant.contains("/")) return courseVariant;
        String[] parts = courseVariant.split("/");
        return parts.length > 1 ? parts[1].trim() : parts[0].trim();
    }

    private double getNumeric(Cell c) {
        if (c == null) return 0.0;
        switch (c.getCellType()) {
            case NUMERIC: return c.getNumericCellValue();
            case STRING:  try { return Double.parseDouble(c.getStringCellValue().trim()); }
            catch (NumberFormatException e) { return 0.0; }
            default:      return 0.0;
        }
    }

    private String getString(Cell c) {
        if (c == null) return "";
        switch (c.getCellType()) {
            case STRING:  return c.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) c.getNumericCellValue());
            case BOOLEAN: return String.valueOf(c.getBooleanCellValue());
            default:      return "";
        }
    }
}
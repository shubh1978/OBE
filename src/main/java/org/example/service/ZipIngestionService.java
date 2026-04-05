package org.example.service;

import lombok.RequiredArgsConstructor;
import org.example.entity.*;
import org.example.repository.*;
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
    //  PROCESS ONE EXCEL FILE - Returns course ID and key for attainment calculation
    // ─────────────────────────────────────────────────────────────────────────
    private Map<String, Object> processExcelFile(InputStream is, String filename) throws Exception {
        try (Workbook wb = new XSSFWorkbook(is)) {

            Sheet sheet = wb.getSheet("EntrySheet");
            if (sheet == null) throw new IllegalStateException("Sheet 'EntrySheet' not found");

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw new IllegalStateException("Header row missing");

            String examType = filename.contains("end_term") ? "end_term" : "mid_term";

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
            // This handles files where question columns might be at different positions
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String h = getString(headerRow.getCell(c));
                if (h.isEmpty()) continue;

                // MAX_MARKS columns → read max value from first data row
                if (h.endsWith("MAX_MARKS") && !h.equals("EVENT_MAX_MARKS")) {
                    String label = h.replace("MAX_MARKS", "").trim();
                    Row first = sheet.getRow(1);
                    if (first != null) {
                        double maxVal = getNumeric(first.getCell(c));
                        if (maxVal > 0) labelToMax.put(label, maxVal);
                    }
                    continue;
                }
                // Skip ID and summary columns
                if (h.endsWith("_ID") || h.endsWith("MARKS_ID") ||
                        h.equals("QUESTION_MARKS_SUM") || h.equals("EVENT_MAX_MARKS")) continue;

                // IMPROVED: More flexible question column detection
                // Accept columns that:
                // 1. Start with Q + digit (Q1, Q2, Q1(a), Q1(b), etc.)
                // 2. Contain "Question" keyword with numbers
                // 3. Match pattern: Q followed by number, with optional spaces/separators
                boolean isQuestionColumn = false;
                String hUpper = h.toUpperCase().trim();
                
                // Pattern 1: Standard Q format - Q1, Q2, Q1(a), Q1(b), Q1 (5), etc.
                if (h.matches("^Q\\s*\\d.*")) {
                    isQuestionColumn = true;
                } 
                // Pattern 2: Question word format - Question 1, Question 2, Question-1, etc.
                else if (hUpper.contains("QUESTION") && hUpper.matches(".*Q\\s*-?\\s*\\d+.*")) {
                    isQuestionColumn = true;
                }
                // Pattern 3: Just Q with number (Q-1, Q 1, etc. with separators)
                else if (h.matches("^Q[\\s\\-_]?\\d+.*")) {
                    isQuestionColumn = true;
                }
                // Pattern 4: Numeric only patterns (should have some indication)
                // Exclude columns that are clearly not questions
                else if (h.matches(".*\\d+.*") && 
                         !h.contains("ID") && !h.contains("MAX") && !h.contains("SUM") &&
                         !h.contains("EVENT") && !h.contains("PROGRAM") && !h.contains("BATCH") &&
                         !h.contains("PERIOD") && !h.contains("STUDENT") && !h.contains("COURSE") &&
                         !h.contains("VARIANT")) {
                    // Additional validation: check if this looks like a question column
                    // by seeing if column name is reasonable (not too long, not generic)
                    if (h.length() <= 20 && !h.matches(".*[A-Z]{3,}.*")) {
                        isQuestionColumn = true;
                    }
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
            // Q1, Q1(a), Q1(b) → CO1
            // Q2, Q2(a), Q2(b) → CO2, etc.
            Map<String, Integer> questionToCoNumber = new HashMap<>();
            for (String qLabel : labelToMarksCol.keySet()) {
                // Extract first digit from question label
                // Q1 → 1, Q1(a) → 1, Q2(b) → 2
                String[] parts = qLabel.replaceAll("[^0-9]", " ").trim().split("\\s+");
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    try {
                        int coNum = Integer.parseInt(parts[0]);
                        questionToCoNumber.put(qLabel, coNum);
                    } catch (NumberFormatException e) {
                        // Skip questions without number prefix
                    }
                }
            }

            // ─────────────────────────────────────────────────────────────────
            // Caches to avoid repeated DB lookups per file
            // ── Caches to avoid repeated DB lookups per file ──────────────────
            Map<String, Student> studentCache  = new HashMap<>();
            Map<String, Course>  courseCache   = new HashMap<>();
            Map<String, Program> programCache  = new HashMap<>();
            Map<String, Batch>   batchCache    = new HashMap<>();
            Map<String, QuestionCOMapping> mappingCache = new HashMap<>();  // NEW: Cache for mappings

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

                // ── Resolve Course ──────────────────────────────────────────────
                // STRICT MATCHING: only use courses that already exist in the DB
                // (created by structure-Excel upload). Do NOT auto-create courses.
                // This prevents phantom data for courses not in any uploaded Excel.
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
                int startYear = parseBatchStartYear(batchStr);
                int endYear   = parseBatchEndYear(batchStr, startYear);
                String batchKey = progName + "_" + startYear;
                Batch batchObj = batchCache.computeIfAbsent(batchKey, k ->
                        batchRepository.findByStartYearAndProgramAndSpecialization(startYear, program, null)
                                .orElse(null));

                if (batchObj == null) {
                    System.out.println("  ✗ [SKIP] Batch not found in DB: " + batchKey + " — skipping row " + r);
                    continue;
                }

                // ── Resolve Semester ────────────────────────────────────────────
                final int semNum = semesterNum;
                final Batch fb = batchObj;
                Semester semester = semesterRepository.findByNumberAndBatch(semNum, fb).orElse(null);

                if (semester == null) {
                    System.out.println("  ✗ [SKIP] Semester " + semNum + " not found for batch " + batchKey + " — skipping row " + r);
                    continue;
                }

                // ── Resolve Course (strict: must already exist in DB) ───────────
                final Program fp = program;
                final Semester fs = semester;
                String courseKey = courseCode.toUpperCase() + "_" + batchKey;
                Course course = courseCache.computeIfAbsent(courseKey, k -> {
                    // Search for existing course by code within this specific batch's semester
                    // Primary: match by course code + same batch
                    List<Course> batchCourses = courseRepository.findByBatch(fb);
                    Course found = batchCourses.stream()
                            .filter(c -> c.getCourseCode().equalsIgnoreCase(courseCode))
                            .findFirst().orElse(null);

                    if (found != null) {
                        System.out.println("  ✓ Matched course: " + found.getId() + " (" + found.getCourseCode() + ") in batch " + batchKey);
                        return found;
                    }

                    // Fallback: match by code + program across batches (same program year range)
                    found = courseRepository.findAll().stream()
                            .filter(c -> c.getCourseCode().equalsIgnoreCase(courseCode))
                            .filter(c -> c.getProgram() != null && c.getProgram().getId().equals(fp.getId()))
                            .findFirst().orElse(null);

                    if (found != null) {
                        System.out.println("  ✓ Matched course by program fallback: " + found.getId() + " (" + found.getCourseCode() + ")");
                        return found;
                    }

                    // NOT FOUND — do NOT auto-create; return null to skip
                    System.out.println("  ✗ [REJECT] Course '" + courseCode + "' not found in DB for program '" + fp.getName() + "'. " +
                            "Upload the structure Excel first. Skipping marks for this course.");
                    return null;
                });

                if (course == null) {
                    // Course not in DB — skip all rows for this course variant
                    continue;
                }

                // Record the course being used
                if (processedCourseId == null) {
                    processedCourseId = course.getId();
                    processedCourseKey = courseKey;
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
                            // Get or create CO
                            String coCode = "CO" + coNum;
                            CO co = coRepository.findByCodeAndCourse(coCode, finalCourse)
                                    .orElseGet(() -> {
                                        CO newCO = new CO();
                                        newCO.setCode(coCode);
                                        newCO.setDescription("Auto-created from question mapping");
                                        newCO.setCourse(finalCourse);
                                        return coRepository.save(newCO);
                                    });
                            
                            // Check if mapping already exists
                            List<QuestionCOMapping> existing = questionCOMappingRepository.findAll().stream()
                                    .filter(m -> m.getCourse().getId().equals(finalCourse.getId()) &&
                                               m.getQuestionLabel().equalsIgnoreCase(qLabel) &&
                                               m.getCo().getId().equals(co.getId()))
                                    .toList();
                            
                            if (existing.isEmpty()) {
                                QuestionCOMapping mapping = new QuestionCOMapping();
                                mapping.setCourse(finalCourse);
                                mapping.setQuestionLabel(qLabel);
                                mapping.setCo(co);
                                mapping.setMaxMarks(maxMarks);
                                questionCOMappingRepository.save(mapping);
                                mappingCache.put(mappingKey, mapping);
                            }
                        } catch (Exception e) {
                            // Log but don't fail the entire upload
                            System.err.println("Warning: Could not create mapping for " + qLabel + ": " + e.getMessage());
                        }
                    }
                }

                // ── Resolve Student ───────────────────────────────────────────
                final Program fprog = program;
                final Batch fbo = batchObj;
                Student student = studentCache.computeIfAbsent(enrollmentNo, id ->
                        studentRepository.findByEnrollmentNumber(id)
                                .orElseGet(() -> {
                                    Student s = new Student();
                                    s.setEnrollmentNumber(id);
                                    s.setName(studentName);
                                    s.setProgram(fprog);
                                    s.setBatch(fbo);
                                    return studentRepository.save(s);
                                }));

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
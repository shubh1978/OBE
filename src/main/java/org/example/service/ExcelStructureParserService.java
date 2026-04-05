package org.example.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.entity.*;
import org.example.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses the OBE_Structure_Template.xlsx file and creates all entities:
 * Program → Specialization → Batch → Semester → Course → CO → PO → PSO
 * CO-PO mappings → CO-PSO mappings
 */
@Service
@RequiredArgsConstructor
public class ExcelStructureParserService {

    private final ProgramRepository        programRepository;
    private final SpecializationRepository specializationRepository;
    private final BatchRepository          batchRepository;
    private final SemesterRepository       semesterRepository;
    private final CourseRepository         courseRepository;
    private final CORepository             coRepository;
    private final PORepository             poRepository;
    private final PSORepository            psoRepository;
    private final CO_PO_MappingRepository  copoRepository;
    private final COPSORepository copsoRepository;

    public Map<String, Object> parse(MultipartFile file) throws Exception {
        Workbook wb;
        try {
            wb = new XSSFWorkbook(file.getInputStream());
        } catch (Exception e) {
            // Try legacy .xls format as fallback
            try {
                wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook(file.getInputStream());
            } catch (Exception e2) {
                throw new IllegalArgumentException(
                        "Cannot read Excel file. Please ensure it is saved as .xlsx format (Excel Workbook). " +
                                "In Excel: File > Save As > Excel Workbook (.xlsx). Error: " + e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        List<String> log = new ArrayList<>();

        // ── Sheet 1: Programme ────────────────────────────────────────
        Sheet s1 = wb.getSheet("1_Programme");
        if (s1 == null) throw new IllegalArgumentException("Sheet '1_Programme' not found");

        String progName    = str(s1, 3, 2);   // row 4, col C (0-indexed: row=3,col=2)
        String specName    = str(s1, 4, 2);
        int    startYear   = num(s1, 5, 2);
        int    endYear     = num(s1, 6, 2);
        int    numSems     = num(s1, 7, 2);
        int    numPOs      = num(s1, 8, 2);
        int    numPSOs     = num(s1, 9, 2);

        if (progName.isBlank()) throw new IllegalArgumentException("Programme name is required in sheet 1_Programme");
        if (startYear == 0)    throw new IllegalArgumentException("Batch Start Year is required");

        log.add("Programme: " + progName + ", Spec: " + specName + ", Batch: " + startYear + "-" + endYear);

        // Create Program
        Program program = programRepository.findByName(progName)
                .orElseGet(() -> programRepository.save(newProgram(progName)));

        // Create Specialization
        Specialization spec = null;
        if (!specName.isBlank()) {
            final Program fp = program;
            spec = specializationRepository.findByNameAndProgram(specName, program)
                    .orElseGet(() -> { Specialization s = new Specialization(); s.setName(specName); s.setProgram(fp); return specializationRepository.save(s); });
        }

        // Create Batch
        final Specialization fspec = spec;
        final Program fp = program;
        final int fsy = startYear, fey = endYear > 0 ? endYear : startYear + 3;
        Batch batch = batchRepository.findFirstByStartYearAndProgramAndSpecialization(startYear, program, spec)
                .orElseGet(() -> { Batch b = new Batch(); b.setStartYear(fsy); b.setEndYear(fey); b.setProgram(fp); b.setSpecialization(fspec); return batchRepository.save(b); });

        log.add("Batch created: id=" + batch.getId());

        // ── Sheet 2: Semesters & Courses ─────────────────────────────
        Sheet s2 = wb.getSheet("2_Semesters");
        if (s2 == null) throw new IllegalArgumentException("Sheet '2_Semesters' not found");

        Map<Integer, Semester> semesterCache = new HashMap<>();
        Map<String, Course>    courseCache   = new HashMap<>();
        int coursesCreated = 0;

        for (int r = 2; r <= s2.getLastRowNum(); r++) {
            Row row = s2.getRow(r);
            if (row == null) continue;
            int semNum = numFromRow(row, 1);    // col B
            String courseName = strFromRow(row, 2); // col C
            String courseCode = strFromRow(row, 3); // col D
            // int numCOs  = numFromRow(row, 4);  // col E — informational only
            // int credits = numFromRow(row, 5);  // col F
            if (semNum == 0 || courseName.isBlank() || courseCode.isBlank()) continue;

            // Get or create Semester
            final int fsn = semNum;
            final Batch fb = batch;
            Semester semester = semesterCache.computeIfAbsent(semNum, k ->
                    semesterRepository.findFirstByNumberAndBatch(fsn, fb)
                            .orElseGet(() -> { Semester s = new Semester(); s.setNumber(fsn); s.setBatch(fb); return semesterRepository.save(s); })
            );

            // Get or create Course — ALWAYS update name and semester
            String key = courseCode + "_" + batch.getId();
            final String fcn = courseName, fcc = courseCode;
            final Semester fsem = semester;
            Course course = courseRepository.findByCourseCodeAndBatch(courseCode, batch)
                    .orElseGet(() -> {
                        Course nc = new Course();
                        nc.setCourseCode(fcc); nc.setCourseName(fcn);
                        nc.setProgram(fp); nc.setSpecialization(fspec);
                        nc.setBatch(fb); nc.setSemester(fsem);
                        return nc;
                    });
            // Always update — name or semester may have changed in Excel
            course.setCourseName(fcn);
            course.setSemester(fsem);
            course.setProgram(fp);
            course.setSpecialization(fspec);
            course.setBatch(fb);
            course = courseRepository.save(course);
            courseCache.put(key, course);
            coursesCreated++;
        }
        log.add("Courses: " + coursesCreated + " created/updated");

        // ── Sheet 3: Course Outcomes ──────────────────────────────────
        Sheet s3 = wb.getSheet("3_CourseOutcomes");
        int cosCreated = 0;
        if (s3 != null) {
            for (int r = 2; r <= s3.getLastRowNum(); r++) {
                Row row = s3.getRow(r);
                if (row == null) continue;
                String courseCode = strFromRow(row, 1);  // col B
                String coNum      = strFromRow(row, 2);  // col C  e.g. "CO1"
                String coDesc     = strFromRow(row, 3);  // col D
                if (courseCode.isBlank() || coNum.isBlank()) continue; // description can be empty

                Course course = courseCache.get(courseCode + "_" + batch.getId());
                if (course == null) {
                    // Try finding by code+batch from DB
                    course = courseRepository.findByCourseCodeAndBatch(courseCode, batch).orElse(null);
                }
                if (course == null) {
                    // Course not in sheet 2 — create it with semester=null, can be linked later
                    final String fcc3 = courseCode;
                    final Program fp3 = program; final Specialization fspec3 = spec;
                    final Batch fb3 = batch;
                    course = courseRepository.findFirstByCourseCode(courseCode)
                            .orElseGet(() -> {
                                Course newC = new Course();
                                newC.setCourseCode(fcc3);
                                newC.setCourseName(fcc3); // will be updated if found in sheet 2
                                newC.setProgram(fp3); newC.setSpecialization(fspec3); newC.setBatch(fb3);
                                return courseRepository.save(newC);
                            });
                    courseCache.put(courseCode + "_" + batch.getId(), course);
                    log.add("AUTO-CREATED course for CO: " + courseCode + " (add it to Sheet 2_Semesters with correct semester)");
                }

                String coCode = courseCode + "-" + coNum;  // e.g. "ENBC101-CO1"
                final Course fc = course;
                final String fcd = coDesc, fcc2 = coCode;
                // Search: full code -> short code -> suffix match -> create new
                CO co = coRepository.findByCodeAndCourse(coCode, fc)
                        .orElseGet(() -> coRepository.findByCodeAndCourse(coNum, fc)
                                .orElseGet(() -> coRepository.findByCourse(fc).stream()
                                        .filter(x -> x.getCode().endsWith("-" + coNum) || x.getCode().equals(coNum))
                                        .findFirst()
                                        .orElseGet(() -> { CO nc = new CO(); nc.setCode(fcc2); nc.setCourse(fc); return nc; })));
                co.setCode(fcc2);          // always normalize to full code
                co.setDescription(fcd);    // always update description
                co.setCourse(fc);
                coRepository.save(co);
                cosCreated++;
                log.add("CO saved: " + fcc2);
            }
        }
        log.add("COs: " + cosCreated + " created/updated");

        // ── Sheet 4: PO & PSO ─────────────────────────────────────────
        Sheet s4 = wb.getSheet("4_PO_PSO");
        int posCreated = 0, psosCreated = 0;
        Map<String, PO>  poCache  = new HashMap<>();
        Map<String, PSO> psoCache = new HashMap<>();

        if (s4 != null) {
            boolean inPSOSection = false;
            for (int r = 2; r <= s4.getLastRowNum(); r++) {
                Row row = s4.getRow(r);
                if (row == null) continue;
                String col1 = strFromRow(row, 1);
                String col2 = strFromRow(row, 2);

                // Detect PSO section header
                if (col1.contains("PSO") && col2.isBlank()) { inPSOSection = true; continue; }
                if (col1.isBlank() && col2.isBlank()) continue;

                if (!inPSOSection && col1.startsWith("PO") && !col2.isBlank()) {
                    final Program ffp = program;
                    final String fcode = col1, fdesc = col2;
                    PO po = poRepository.findByCode(col1)
                            .orElseGet(() -> { PO p = new PO(); p.setCode(fcode); p.setProgram(ffp); return p; });
                    po.setDescription(fdesc);  // always update description
                    po.setProgram(ffp);
                    poCache.put(col1, poRepository.save(po));
                    posCreated++;
                } else if (inPSOSection && col1.startsWith("PSO") && !col2.isBlank()) {
                    final Program ffp = program;
                    final String fcode = col1, fdesc = col2;
                    PSO pso = psoRepository.findByCode(col1)
                            .orElseGet(() -> { PSO p = new PSO(); p.setCode(fcode); p.setProgram(ffp); return p; });
                    pso.setDescription(fdesc);  // always update description
                    pso.setProgram(ffp);
                    psoCache.put(col1, psoRepository.save(pso));
                    psosCreated++;
                }
            }
        }
        log.add("POs: " + posCreated + ", PSOs: " + psosCreated);

        // Reload PO/PSO caches if they were already in DB
        if (poCache.isEmpty()) {
            poRepository.findByProgram(program).forEach(p -> poCache.put(p.getCode(), p));
        }
        if (psoCache.isEmpty()) {
            psoRepository.findByProgram(program).forEach(p -> psoCache.put(p.getCode(), p));
        }

        // Build CO cache from DB - key = "COURSECOODE-CON" e.g. "ENBC101-CO1"
        Map<String, CO> coCache = new HashMap<>();
        courseCache.values().forEach(course2 ->
                coRepository.findByCourse(course2).forEach(co -> {
                    coCache.put(co.getCode(), co); // full code "ENBC101-CO1"
                    // Also index by just the CO part "CO1" under course context
                    String shortKey = course2.getCourseCode() + "-" + co.getCode().replaceAll(".*-", "");
                    coCache.put(shortKey, co);
                })
        );

        // ── Sheet 5: CO-PO Matrix ────────────────────────────────────
        Sheet s5 = wb.getSheet("5_CO_PO_Matrix");
        int copoCreated = 0;
        if (s5 != null) {
            // Read PO headers from row 2 (0-indexed row 1)
            Row hdrRow = s5.getRow(1);
            List<String> poHeaders = new ArrayList<>();
            if (hdrRow != null) {
                for (int c = 3; c < hdrRow.getLastCellNum(); c++) {
                    String h = strFromCell(hdrRow.getCell(c));
                    if (!h.isBlank()) poHeaders.add(h); else poHeaders.add("");
                }
            }

            for (int r = 2; r <= s5.getLastRowNum(); r++) {
                Row row = s5.getRow(r);
                if (row == null) continue;
                String courseCode = strFromRow(row, 1);
                String coNum      = strFromRow(row, 2);
                if (courseCode.isBlank() || coNum.isBlank()) continue;

                String coKey = courseCode + "-" + coNum;  // full: "ENBC101-CO1"
                CO co = coCache.get(coKey);
                if (co == null) {
                    Course course = courseRepository.findByCourseCodeAndBatch(courseCode, batch)
                            .orElseGet(() -> courseRepository.findFirstByCourseCode(courseCode).orElse(null));
                    if (course != null) {
                        final Course fc2 = course;
                        co = coRepository.findByCodeAndCourse(coKey, course)
                                .orElseGet(() -> coRepository.findByCodeAndCourse(coNum, fc2)
                                        .orElseGet(() -> coRepository.findByCourse(fc2).stream()
                                                .filter(x -> x.getCode().endsWith("-" + coNum) || x.getCode().equals(coNum))
                                                .findFirst().orElse(null)));
                    }
                }
                if (co == null) {
                    log.add("SKIP CO-PO: CO not found for " + courseCode + "-" + coNum);
                    continue;
                }

                for (int i = 0; i < poHeaders.size(); i++) {
                    String poCode = poHeaders.get(i);
                    if (poCode.isBlank()) continue;
                    String val = strFromRow(row, i + 3);
                    if (val.isBlank() || val.equals("-")) continue;
                    try {
                        int weight = Integer.parseInt(val.trim());
                        if (weight < 1 || weight > 3) continue;
                        PO po = poCache.get(poCode);
                        if (po == null) po = poRepository.findByCode(poCode).orElse(null);
                        if (po == null) { log.add("SKIP CO-PO: PO not found: " + poCode); continue; }
                        final CO fco = co; final PO fpo = po; final int fw = weight;
                        // Find existing mapping for this exact CO-PO pair (there should be only 1)
                        Optional<COPOMap> existing = copoRepository.findByCoId(co.getId()).stream()
                                .filter(m -> m.getPo().getId().equals(fpo.getId()))
                                .reduce((a, b) -> a.getWeight() >= b.getWeight() ? a : b); // keep highest if dups
                        COPOMap mapping = existing.orElseGet(() -> { COPOMap m = new COPOMap(); m.setCo(fco); m.setPo(fpo); return m; });
                        mapping.setWeight(fw);
                        copoRepository.save(mapping);
                        copoCreated++;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        log.add("CO-PO mappings: " + copoCreated);

        // ── Sheet 6: CO-PSO Matrix ───────────────────────────────────
        Sheet s6 = wb.getSheet("6_CO_PSO_Matrix");
        int copsoCreated = 0;
        if (s6 != null) {
            Row hdrRow = s6.getRow(1);
            List<String> psoHeaders = new ArrayList<>();
            if (hdrRow != null) {
                for (int c = 3; c < hdrRow.getLastCellNum(); c++) {
                    String h = strFromCell(hdrRow.getCell(c));
                    if (!h.isBlank()) psoHeaders.add(h); else psoHeaders.add("");
                }
            }

            for (int r = 2; r <= s6.getLastRowNum(); r++) {
                Row row = s6.getRow(r);
                if (row == null) continue;
                String courseCode = strFromRow(row, 1);
                String coNum      = strFromRow(row, 2);
                if (courseCode.isBlank() || coNum.isBlank()) continue;

                String coKey = courseCode + "-" + coNum;  // e.g. "ENBC101-CO1"
                CO co = coCache.get(coKey);
                if (co == null) {
                    Course course = courseRepository.findByCourseCodeAndBatch(courseCode, batch)
                            .orElseGet(() -> courseRepository.findFirstByCourseCode(courseCode).orElse(null));
                    if (course != null) {
                        final Course fc3 = course;
                        co = coRepository.findByCodeAndCourse(coKey, course)
                                .orElseGet(() -> coRepository.findByCodeAndCourse(coNum, fc3)
                                        .orElseGet(() -> coRepository.findByCourse(fc3).stream()
                                                .filter(x -> x.getCode().endsWith("-" + coNum) || x.getCode().equals(coNum))
                                                .findFirst().orElse(null)));
                    }
                }
                if (co == null) {
                    log.add("SKIP CO-PSO: CO not found for " + courseCode + "-" + coNum);
                    continue;
                }

                for (int i = 0; i < psoHeaders.size(); i++) {
                    String psoCode = psoHeaders.get(i);
                    if (psoCode.isBlank()) continue;
                    String val = strFromRow(row, i + 3);
                    if (val.isBlank() || val.equals("-")) continue;
                    try {
                        int weight = Integer.parseInt(val.trim());
                        if (weight < 1 || weight > 3) continue;
                        PSO pso = psoCache.get(psoCode);
                        if (pso == null) pso = psoRepository.findByCode(psoCode).orElse(null);
                        if (pso == null) { log.add("SKIP CO-PSO: PSO not found: " + psoCode); continue; }
                        final CO fco = co; final PSO fpso = pso; final int fw = weight;
                        Optional<COPSOMapping> existingPso = copsoRepository.findByCoId(co.getId()).stream()
                                .filter(m -> m.getPso().getId().equals(fpso.getId()))
                                .reduce((a, b) -> a.getWeight() >= b.getWeight() ? a : b);
                        COPSOMapping mapping = existingPso.orElseGet(() -> { COPSOMapping m = new COPSOMapping(); m.setCo(fco); m.setPso(fpso); return m; });
                        mapping.setWeight(fw);
                        copsoRepository.save(mapping);
                        copsoCreated++;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        log.add("CO-PSO mappings: " + copsoCreated);

        wb.close();

        result.put("status",       "success");
        result.put("programme",    progName);
        result.put("specialization", specName);
        result.put("batch",        startYear + "-" + endYear);
        result.put("batch_id",     batch.getId());
        result.put("semesters",    semesterCache.size());
        result.put("courses",      coursesCreated);
        result.put("cos",          cosCreated);
        result.put("pos",          posCreated);
        result.put("psos",         psosCreated);
        result.put("copo_mappings",copoCreated);
        result.put("copso_mappings",copsoCreated);
        result.put("log",          log);
        return result;
    }

    // ── VERIFY: compare Excel with DB ────────────────────────────────
    public Map<String, Object> verify(MultipartFile file) throws Exception {
        Workbook wb;
        try {
            wb = new XSSFWorkbook(file.getInputStream());
        } catch (Exception e) {
            // Try legacy .xls format as fallback
            try {
                wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook(file.getInputStream());
            } catch (Exception e2) {
                throw new IllegalArgumentException(
                        "Cannot read Excel file. Please ensure it is saved as .xlsx format (Excel Workbook). " +
                                "In Excel: File > Save As > Excel Workbook (.xlsx). Error: " + e.getMessage());
            }
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        List<Map<String, Object>> matrices = new ArrayList<>();

        // ── Sheet 1: Programme ────────────────────────────────────────
        Sheet s1 = wb.getSheet("1_Programme");
        String progName  = str(s1, 3, 2);
        String specName  = str(s1, 4, 2);
        int startYear    = num(s1, 5, 2);

        Program program = programRepository.findByName(progName).orElse(null);
        checks.add(check("Programme: " + progName, progName, program != null ? program.getName() : null));

        Specialization spec = null;
        if (!specName.isBlank() && program != null) {
            spec = specializationRepository.findByNameAndProgram(specName, program).orElse(null);
            checks.add(check("Specialization: " + specName, specName, spec != null ? spec.getName() : null));
        }

        Batch batch = program != null
                ? batchRepository.findFirstByStartYearAndProgramAndSpecialization(startYear, program, spec).orElse(null)
                : null;
        checks.add(check("Batch start year: " + startYear, String.valueOf(startYear),
                batch != null ? String.valueOf(batch.getStartYear()) : null));

        // ── Sheet 2: Courses ──────────────────────────────────────────
        Sheet s2 = wb.getSheet("2_Semesters");
        int excelCourseCount = 0;
        int dbCourseCount = 0;
        List<String> missingCourses = new ArrayList<>();

        if (s2 != null && batch != null) {
            for (int r = 2; r <= s2.getLastRowNum(); r++) {
                Row row = s2.getRow(r);
                if (row == null) continue;
                String code = strFromRow(row, 3);
                int semNum  = numFromRow(row, 1);
                if (code.isBlank()) continue;
                excelCourseCount++;

                Course course = courseRepository.findByCourseCodeAndBatch(code, batch).orElse(null);
                if (course == null) missingCourses.add(code);
                else {
                    dbCourseCount++;
                    // Check semester
                    Integer dbSem = course.getSemester() != null ? course.getSemester().getNumber() : null;
                    if (dbSem == null || dbSem != semNum)
                        checks.add(check("Semester for " + code, String.valueOf(semNum), String.valueOf(dbSem)));
                }
            }
            checks.add(check("Total courses", String.valueOf(excelCourseCount), String.valueOf(dbCourseCount)));
            if (!missingCourses.isEmpty())
                checks.add(warnCheck("Missing courses: " + String.join(", ", missingCourses)));
        }

        // ── Sheet 3: COs ──────────────────────────────────────────────
        Sheet s3 = wb.getSheet("3_CourseOutcomes");
        int excelCOs = 0, dbCOs = 0;
        if (s3 != null && batch != null) {
            for (int r = 2; r <= s3.getLastRowNum(); r++) {
                Row row = s3.getRow(r);
                if (row == null) continue;
                String code  = strFromRow(row, 1);
                String coNum = strFromRow(row, 2);
                String desc  = strFromRow(row, 3);
                if (code.isBlank() || coNum.isBlank()) continue;
                excelCOs++;
                Course course = courseRepository.findByCourseCodeAndBatch(code, batch).orElse(null);
                if (course == null) continue;
                CO co = coRepository.findByCodeAndCourse(coNum, course).orElse(null);
                if (co == null) checks.add(check("CO " + coNum + " for " + code, coNum, null));
                else { dbCOs++; if (co.getDescription() == null || co.getDescription().isBlank())
                    checks.add(warnCheck("CO " + code + "-" + coNum + " has no description")); }
            }
            checks.add(check("Total COs", String.valueOf(excelCOs), String.valueOf(dbCOs)));
        }

        // ── Sheet 4: POs ──────────────────────────────────────────────
        Sheet s4 = wb.getSheet("4_PO_PSO");
        int excelPOs = 0, dbPOs = 0, excelPSOs = 0, dbPSOs = 0;
        if (s4 != null && program != null) {
            boolean inPSO = false;
            for (int r = 2; r <= s4.getLastRowNum(); r++) {
                Row row = s4.getRow(r);
                if (row == null) continue;
                String col1 = strFromRow(row, 1), col2 = strFromRow(row, 2);
                if (col1.contains("PSO") && col2.isBlank()) { inPSO = true; continue; }
                if (col1.isBlank()) continue;
                if (!inPSO && col1.startsWith("PO")) {
                    excelPOs++;
                    PO po = poRepository.findByCode(col1).orElse(null);
                    if (po == null) checks.add(check("PO: " + col1, col1, null));
                    else dbPOs++;
                } else if (inPSO && col1.startsWith("PSO")) {
                    excelPSOs++;
                    PSO pso = psoRepository.findByCode(col1).orElse(null);
                    if (pso == null) checks.add(check("PSO: " + col1, col1, null));
                    else dbPSOs++;
                }
            }
            checks.add(check("POs", String.valueOf(excelPOs), String.valueOf(dbPOs)));
            checks.add(check("PSOs", String.valueOf(excelPSOs), String.valueOf(dbPSOs)));
        }

        // ── Sheet 5: CO-PO Matrix ─────────────────────────────────────
        Sheet s5 = wb.getSheet("5_CO_PO_Matrix");
        if (s5 != null && batch != null) {
            Row hdrRow = s5.getRow(1);
            List<String> poHeaders = new ArrayList<>();
            if (hdrRow != null)
                for (int col = 3; col < hdrRow.getLastCellNum(); col++) {
                    String h = strFromCell(hdrRow.getCell(col));
                    if (!h.isBlank()) poHeaders.add(h);
                }

            // Group rows by course
            Map<String, List<Row>> byCourse = new LinkedHashMap<>();
            for (int r = 2; r <= s5.getLastRowNum(); r++) {
                Row row = s5.getRow(r);
                if (row == null) continue;
                String code = strFromRow(row, 1);
                if (!code.isBlank()) byCourse.computeIfAbsent(code, k -> new ArrayList<>()).add(row);
            }

            for (Map.Entry<String, List<Row>> entry : byCourse.entrySet()) {
                String courseCode = entry.getKey();
                Course course = courseRepository.findByCourseCodeAndBatch(courseCode, batch).orElse(null);
                if (course == null) {
                    // Try finding by code only across any batch
                    course = courseRepository.findFirstByCourseCode(courseCode).orElse(null);
                }
                if (course == null) continue;

                List<CO> dbCOList = coRepository.findByCourse(course);
                Map<String, CO> dbCoMap = new LinkedHashMap<>();
                dbCOList.forEach(co -> dbCoMap.put(co.getCode().replaceAll(".*-", ""), co));

                boolean hasErrors = false;
                List<Map<String, Object>> rows = new ArrayList<>();

                for (Row row : entry.getValue()) {
                    String coNum = strFromRow(row, 2);
                    if (coNum.isBlank()) continue;
                    CO co = dbCoMap.get(coNum);

                    Map<String, Integer> dbWeights = new LinkedHashMap<>();
                    if (co != null && co.getId() != null) copoRepository.findByCoId(co.getId()).forEach(m -> dbWeights.put(m.getPo().getCode(), m.getWeight()));

                    Map<String, Object> rowData = new LinkedHashMap<>();
                    rowData.put("co", coNum);
                    Map<String, Object> expected = new LinkedHashMap<>();
                    Map<String, Object> actual   = new LinkedHashMap<>();

                    for (int i = 0; i < poHeaders.size(); i++) {
                        String po = poHeaders.get(i);
                        String excelVal = strFromRow(row, i + 3);
                        int excelW = (excelVal.isBlank() || excelVal.equals("-")) ? 0 : Integer.parseInt(excelVal.trim());
                        int dbW = dbWeights.getOrDefault(po, 0);
                        expected.put(po, excelW == 0 ? "-" : String.valueOf(excelW));
                        actual.put(po, dbW == 0 ? "-" : String.valueOf(dbW));
                        if (excelW != dbW) hasErrors = true;
                    }
                    rowData.put("expected", expected);
                    rowData.put("actual",   actual);
                    rows.add(rowData);
                }

                Map<String, Object> matrixData = new LinkedHashMap<>();
                matrixData.put("courseCode", courseCode);
                matrixData.put("poHeaders",  poHeaders);
                matrixData.put("rows",       rows);
                matrixData.put("hasErrors",  hasErrors);
                matrices.add(matrixData);
                if (!hasErrors) checks.add(okCheck("CO-PO Matrix: " + courseCode));
                else checks.add(check("CO-PO Matrix: " + courseCode, "matches Excel", "MISMATCH found"));
            }
        }

        wb.close();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checks",  checks);
        result.put("matrices", matrices);
        return result;
    }

    private Map<String, Object> check(String label, String expected, String actual) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label",    label);
        m.put("expected", expected);
        m.put("actual",   actual);
        boolean ok = expected != null && expected.equals(actual);
        m.put("status",   actual == null ? "MISSING" : ok ? "OK" : "MISMATCH");
        return m;
    }
    private Map<String, Object> okCheck(String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label); m.put("expected","matches"); m.put("actual","matches"); m.put("status","OK"); return m;
    }
    private Map<String, Object> warnCheck(String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label); m.put("expected","—"); m.put("actual","—"); m.put("status","WARN"); return m;
    }

    // ── HELPERS ──────────────────────────────────────────────────────
    /** Read string from sheet at 0-based (row, col) — Excel row 4 = index 3 */
    private String str(Sheet sheet, int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null) return "";
        return strFromCell(r.getCell(col));
    }

    private int num(Sheet sheet, int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null) return 0;
        Cell c = r.getCell(col);
        if (c == null) return 0;
        if (c.getCellType() == CellType.NUMERIC) return (int) c.getNumericCellValue();
        try { return Integer.parseInt(strFromCell(c).trim()); } catch (Exception e) { return 0; }
    }

    private String strFromRow(Row row, int colIndex) {
        if (row == null) return "";
        return strFromCell(row.getCell(colIndex));
    }

    private int numFromRow(Row row, int colIndex) {
        if (row == null) return 0;
        Cell c = row.getCell(colIndex);
        if (c == null) return 0;
        if (c.getCellType() == CellType.NUMERIC) return (int) c.getNumericCellValue();
        try { return Integer.parseInt(strFromCell(c).trim()); } catch (Exception e) { return 0; }
    }

    private String strFromCell(Cell c) {
        if (c == null) return "";
        switch (c.getCellType()) {
            case STRING:  return c.getStringCellValue().trim().replaceAll("\\s+", " ");
            case NUMERIC:
                double d = c.getNumericCellValue();
                return d == (long)d ? String.valueOf((long)d) : String.valueOf(d);
            case BOOLEAN: return String.valueOf(c.getBooleanCellValue());
            case BLANK:   return "";
            default:      return "";
        }
    }

    private Program newProgram(String name) {
        Program p = new Program(); p.setName(name); return p;
    }
}
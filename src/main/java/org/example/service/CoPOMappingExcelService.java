package org.example.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.entity.*;
import org.example.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * Parses the "CO PO MAPPING SHEET" Excel format:
 *
 * Rows 1–~18 : PO definitions  — col A = "PO 1" .. "PO 7", col B = short name, col D = description
 * Rows ~21–26: PSO definitions — col A = "PSO 1" .. "PSO n", col B = description
 * Rows ~30+  : Mapping table
 *              A = Course Code (merged, only on first CO row)
 *              B = Course Name (merged)
 *              C = CO code (CO1, CO2 …)
 *              D = CO description
 *              E..K = PO1..PO7 weights  (or however many POs)
 *              L..Q = PSO1..PSO6 weights
 *              R = Semester label ("Semester 1", "semester 2" …)
 *
 * The parser:
 *  • finds or creates Program, Specialization, Batch, Semester, Course, CO, PO, PSO
 *  • upserts CO-PO and CO-PSO mappings
 */
@Service
@RequiredArgsConstructor
public class CoPOMappingExcelService {

    private final ProgramRepository       programRepository;
    private final SpecializationRepository specializationRepository;
    private final BatchRepository          batchRepository;
    private final SemesterRepository       semesterRepository;
    private final CourseRepository         courseRepository;
    private final CORepository             coRepository;
    private final PORepository             poRepository;
    private final PSORepository            psoRepository;
    private final CO_PO_MappingRepository  copoRepository;
    private final COPSORepository          copsoRepository;

    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> parseExcel(
            MultipartFile file,
            String programName,        // e.g. "BSc"
            String specializationName, // e.g. "Computer Science"
            Integer startYear,         // e.g. 2025
            Integer endYear            // e.g. 2027
    ) throws Exception {

        Workbook wb = new XSSFWorkbook(file.getInputStream());
        Sheet sheet = wb.getSheetAt(0);
        wb.close();

        // ── Resolve Program ───────────────────────────────────────────────────
        final String pName = (programName != null && !programName.isBlank()) ? programName.trim() : "BSc";
        Program program = programRepository.findByName(pName)
                .orElseGet(() -> programRepository.save(newProgram(pName)));

        // ── Resolve Specialization ────────────────────────────────────────────
        Specialization spec = null;
        if (specializationName != null && !specializationName.isBlank()) {
            final Program fp = program;
            final String sName = specializationName.trim();
            spec = specializationRepository.findByNameAndProgram(sName, program)
                    .orElseGet(() -> {
                        Specialization s = new Specialization();
                        s.setName(sName);
                        s.setProgram(fp);
                        return specializationRepository.save(s);
                    });
        }

        // ── Resolve Batch ─────────────────────────────────────────────────────
        final int sy = (startYear != null) ? startYear : 2025;
        final int ey = (endYear   != null) ? endYear   : sy + 3;
        final Specialization fspec = spec;
        final Program fprog = program;
        Batch batch = batchRepository.findByStartYearAndProgramAndSpecialization(sy, program, spec)
                .orElseGet(() -> {
                    Batch b = new Batch();
                    b.setStartYear(sy);
                    b.setEndYear(ey);
                    b.setProgram(fprog);
                    b.setSpecialization(fspec);
                    return batchRepository.save(b);
                });

        // ── Scan rows ─────────────────────────────────────────────────────────
        // Phase 1: collect PO and PSO header definitions
        // Phase 2: parse the mapping table

        // We'll do a single pass and switch modes based on content
        boolean inMappingTable  = false;
        boolean foundPoSection  = false;
        boolean foundPsoSection = false;

        // Ordered list of PO / PSO column positions (col index) in the mapping table
        // These are determined from the header row of the mapping table
        List<PO>  orderedPOs  = new ArrayList<>();
        List<PSO> orderedPSOs = new ArrayList<>();

        // Temporarily store PO/PSO definitions before upserting (so we can order them correctly)
        // key = "PO1" / "PSO1", value = description
        Map<String, String> poDefMap  = new LinkedHashMap<>();
        Map<String, String> psoDefMap = new LinkedHashMap<>();

        // Track mapping table header col positions
        // col -> "POx" or "PSOx" or "SEMESTER"
        Map<Integer, String> colRole = new LinkedHashMap<>();

        int poCount = 0, psoCount = 0, courseCount = 0, coCount = 0, copoCount = 0, copsoCount = 0;
        Set<String> createdCourses = new LinkedHashSet<>();

        // For carry-forward (merged cells in col A / B / R)
        String lastCourseCode = null;
        String lastCourseName = null;
        String lastSemesterLabel = null;

        for (Row row : sheet) {
            if (row == null) continue;

            String colA = str(row.getCell(0));
            String colB = str(row.getCell(1));

            // ── Detect PO section ─────────────────────────────────────────────
            if (!inMappingTable && colA.toUpperCase().startsWith("PO")) {
                // e.g. "PO 1" or "PO1"
                String poCode = colA.replaceAll("\\s+", "");  // "PO1"
                // col D (index 3) = long description; col B = short name
                String desc = str(row.getCell(3));
                if (desc.isBlank()) desc = colB; // fallback to short name
                if (!poCode.isEmpty() && !desc.isBlank())
                    poDefMap.put(poCode, desc);
                foundPoSection = true;
                continue;
            }

            // ── Detect PSO section ────────────────────────────────────────────
            if (!inMappingTable && colA.toUpperCase().startsWith("PSO")) {
                String psoCode = colA.replaceAll("\\s+", "");
                String desc = colB;
                if (!psoCode.isEmpty() && !desc.isBlank())
                    psoDefMap.put(psoCode, desc);
                foundPsoSection = true;
                continue;
            }

            // ── Detect mapping table header row ──────────────────────────────
            // Header row: col A has "Course\nCode" or "Course Code", col C has "Course Outcomes"
            if (!inMappingTable) {
                String colC = str(row.getCell(2));
                if ((colA.toLowerCase().contains("course") && colA.toLowerCase().contains("code"))
                        || (colC.toLowerCase().contains("course") && colC.toLowerCase().contains("outcome"))
                        || (colA.toLowerCase().contains("course") && colB.toLowerCase().contains("name"))) {
                    // This is the header row — scan all columns for PO/PSO labels
                    colRole.clear();
                    for (int ci = 0; ci < row.getLastCellNum(); ci++) {
                        String h = str(row.getCell(ci)).trim().replaceAll("\\s+", "").toUpperCase();
                        if (h.matches("PO\\d+"))  colRole.put(ci, h);
                        if (h.matches("PSO\\d+")) colRole.put(ci, h);
                        if (h.equalsIgnoreCase("SEMESTER")) colRole.put(ci, "SEMESTER");
                    }

                    // Now upsert PO / PSO entities from the definition maps
                    for (Map.Entry<String, String> e : poDefMap.entrySet()) {
                        upsertPO(e.getKey(), e.getValue(), program);
                        poCount++;
                    }
                    for (Map.Entry<String, String> e : psoDefMap.entrySet()) {
                        upsertPSO(e.getKey(), e.getValue(), program, spec);
                        psoCount++;
                    }

                    // Build ordered PO/PSO lists from colRole
                    List<String> poKeys  = new ArrayList<>();
                    List<String> psoKeys = new ArrayList<>();
                    for (String role : colRole.values()) {
                        if (role.startsWith("PO"))  poKeys.add(role);
                        if (role.startsWith("PSO")) psoKeys.add(role);
                    }
                    for (String k : poKeys)  orderedPOs.add(poRepository.findByCodeAndProgram(k, program).orElse(null));
                    for (String k : psoKeys) orderedPSOs.add(psoRepository.findByCodeAndProgram(k, program).orElse(null));

                    inMappingTable = true;
                    continue;
                }
            }

            // ── Parse mapping table data rows ─────────────────────────────────
            if (!inMappingTable) continue;

            // Carry-forward course code/name from merged cells
            if (!colA.isBlank() && !colA.toLowerCase().contains("co po")) lastCourseCode = colA.trim().toUpperCase();
            if (!colB.isBlank()) lastCourseName = colB.trim();

            String colC = str(row.getCell(2)); // CO code: "CO1", "CO2"…
            String colD = str(row.getCell(3)); // CO description

            // Semester carry-forward (col R = index 17, but use colRole)
            // Find semester col index
            for (Map.Entry<Integer, String> e : colRole.entrySet()) {
                if ("SEMESTER".equals(e.getValue())) {
                    String sv = str(row.getCell(e.getKey()));
                    if (!sv.isBlank()) lastSemesterLabel = sv.trim();
                    break;
                }
            }

            // Must have a CO code to process
            if (!colC.toUpperCase().matches("CO\\d+")) continue;
            if (lastCourseCode == null || lastCourseCode.isBlank()) continue;

            // ── Get or create Semester ────────────────────────────────────────
            int semNum = parseSemesterNumber(lastSemesterLabel);
            final Batch fb = batch;
            Semester semester = semesterRepository.findByNumberAndBatch(semNum, batch)
                    .orElseGet(() -> {
                        Semester s = new Semester();
                        s.setNumber(semNum);
                        s.setBatch(fb);
                        return semesterRepository.save(s);
                    });

            // ── Get or create Course ──────────────────────────────────────────
            final String fcc = lastCourseCode;
            final String fcn = lastCourseName != null ? lastCourseName : lastCourseCode;
            final Specialization fsp = spec;
            final Program fpr = program;
            final Semester fsem = semester;
            Course course = courseRepository.findByCourseCodeAndBatch(fcc, batch)
                    .orElseGet(() -> {
                        Course c = new Course();
                        c.setCourseCode(fcc);
                        c.setCourseName(fcn);
                        c.setProgram(fpr);
                        c.setSemester(fsem);
                        c.setBatch(fb);
                        c.setSpecialization(fsp);
                        return courseRepository.save(c);
                    });
            if (createdCourses.add(fcc)) courseCount++;

            // ── Get or create CO ──────────────────────────────────────────────
            final String coCode  = colC.trim().toUpperCase(); // "CO1"
            final String fullCoCode = fcc + "-" + coCode;    // "ETCCCS101-CO1"
            final String coDesc  = colD.trim().isBlank() ? "Auto CO" : colD.trim();
            final Course fc = course;
            CO co = coRepository.findByCodeAndCourse(coCode, course)
                    .orElseGet(() -> {
                        // Also try full code
                        return coRepository.findByCodeAndCourse(fullCoCode, fc)
                                .orElseGet(() -> {
                                    CO newCo = new CO();
                                    newCo.setCode(fullCoCode);
                                    newCo.setDescription(coDesc);
                                    newCo.setCourse(fc);
                                    return coRepository.save(newCo);
                                });
                    });
            coCount++;

            // ── Upsert CO-PO and CO-PSO weights ──────────────────────────────
            final CO finalCo = co;
            for (Map.Entry<Integer, String> colEntry : colRole.entrySet()) {
                int ci    = colEntry.getKey();
                String role = colEntry.getValue();
                if ("SEMESTER".equals(role)) continue;

                Cell cell = row.getCell(ci);
                if (cell == null) continue;

                Integer weight = getCellInt(cell);
                if (weight == null || weight < 1 || weight > 3) continue;

                if (role.startsWith("PO")) {
                    int idx = Integer.parseInt(role.substring(2)) - 1;
                    if (idx >= orderedPOs.size()) continue;
                    PO po = orderedPOs.get(idx);
                    if (po == null) continue;
                    final int w = weight; final PO fpo = po;
                    COPOMap map = copoRepository.findByCo(finalCo).stream()
                            .filter(x -> x.getPo().getId().equals(fpo.getId()))
                            .findFirst()
                            .orElseGet(() -> { COPOMap n = new COPOMap(); n.setCo(finalCo); n.setPo(fpo); return n; });
                    if (map.getWeight() != w) { map.setWeight(w); copoRepository.save(map); copoCount++; }

                } else if (role.startsWith("PSO")) {
                    int idx = Integer.parseInt(role.substring(3)) - 1;
                    if (idx >= orderedPSOs.size()) continue;
                    PSO pso = orderedPSOs.get(idx);
                    if (pso == null) continue;
                    final int w = weight; final PSO fpso = pso;
                    COPSOMapping map = copsoRepository.findByCo(finalCo).stream()
                            .filter(x -> x.getPso().getId().equals(fpso.getId()))
                            .findFirst()
                            .orElseGet(() -> { COPSOMapping n = new COPSOMapping(); n.setCo(finalCo); n.setPso(fpso); return n; });
                    if (map.getWeight() != w) { map.setWeight(w); copsoRepository.save(map); copsoCount++; }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("program", pName);
        result.put("specialization", specializationName);
        result.put("batch", sy + "-" + ey);
        result.put("pos_upserted", poCount);
        result.put("psos_upserted", psoCount);
        result.put("courses_created_or_found", courseCount);
        result.put("cos_created", coCount);
        result.put("copo_mappings_upserted", copoCount);
        result.put("copso_mappings_upserted", copsoCount);
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void upsertPO(String code, String desc, Program program) {
        poRepository.findByCodeAndProgram(code, program).ifPresentOrElse(
                po -> { if (po.getDescription() == null || po.getDescription().length() < 5) { po.setDescription(desc); poRepository.save(po); } },
                () -> { PO po = new PO(); po.setCode(code); po.setDescription(desc); po.setProgram(program); poRepository.save(po); }
        );
    }

    private void upsertPSO(String code, String desc, Program program, Specialization spec) {
        psoRepository.findByCodeAndProgram(code, program).ifPresentOrElse(
                pso -> { if (pso.getDescription() == null || pso.getDescription().length() < 5) { pso.setDescription(desc); psoRepository.save(pso); } },
                () -> { PSO pso = new PSO(); pso.setCode(code); pso.setDescription(desc); pso.setProgram(program);
                        if (spec != null) pso.setSpecialization(spec);
                        psoRepository.save(pso); }
        );
    }

    private int parseSemesterNumber(String label) {
        if (label == null || label.isBlank()) return 1;
        // "Semester 1" / "semester 2" / "1"
        label = label.toLowerCase().replaceAll("[^0-9]", " ").trim();
        String[] parts = label.split("\\s+");
        for (String p : parts) {
            try { return Integer.parseInt(p); } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    private String str(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            default:      return "";
        }
    }

    private Integer getCellInt(Cell cell) {
        if (cell == null) return null;
        try {
            switch (cell.getCellType()) {
                case NUMERIC: int v = (int) cell.getNumericCellValue(); return v;
                case STRING:  return Integer.parseInt(cell.getStringCellValue().trim());
                default:      return null;
            }
        } catch (Exception e) { return null; }
    }

    private Program newProgram(String name) {
        Program p = new Program(); p.setName(name); return p;
    }
}

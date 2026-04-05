package org.example.service;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.entity.*;
import org.example.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Handbook Parser — v3 (multi-program, batch-aware)
 *
 * What it reliably parses from PDF:
 * ✓ Program name (from title page)
 * ✓ Specialization (from title page)
 * ✓ Batch years (from "2023-24" or "2023-26" on title page)
 * ✓ PO codes + descriptions
 * ✓ PSO codes + descriptions
 * ✓ Semester numbers (I → VIII)
 * ✓ Course codes (ENBC101, ENCA201 etc.) + course names
 * ✓ CO codes (CO1..CO6) per course
 * ✓ CO descriptions (text after CO1 label)
 * ~ CO-PO weights (best-effort — PDF tables often garbled)
 *
 * What needs manual input via the Upload page:
 * - Program name override (if auto-detection fails)
 * - Batch start year override
 */
@Service
@RequiredArgsConstructor
public class HandbookParserService {

    private final ProgramRepository programRepository;
    private final SpecializationRepository specializationRepository;
    private final BatchRepository batchRepository;
    private final SemesterRepository semesterRepository;
    private final CourseRepository courseRepository;
    private final CORepository coRepository;
    private final PORepository poRepository;
    private final PSORepository psoRepository;
    private final CO_PO_MappingRepository copoRepository;
    private final COPSORepository copsoRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN ENTRY
    // programNameOverride and batchStartYearOverride are optional manual inputs
    // passed from the UI when auto-detection is uncertain
    // ─────────────────────────────────────────────────────────────────────────
    // Convenience overload — called by old HandbookUploadController
    public Map<String, Object> parseHandbook(MultipartFile file) throws Exception {
        return parseHandbook(file, null, null);
    }

    public Map<String, Object> parseHandbook(
            MultipartFile file,
            String programNameOverride, // nullable — e.g. "BCA", "BTech", "BSc"
            Integer batchStartYearOverride // nullable — e.g. 2023
    ) throws Exception {

        PDDocument doc = PDDocument.load(file.getInputStream());
        PDFTextStripper stripper = new PDFTextStripper();
        int totalPages = doc.getNumberOfPages();

        // ── Extract all pages ─────────────────────────────────────────────────
        List<String> pages = new ArrayList<>();
        for (int p = 1; p <= totalPages; p++) {
            stripper.setStartPage(p);
            stripper.setEndPage(p);
            pages.add(stripper.getText(doc));
        }
        doc.close();

        String fullText = String.join("\n", pages);

        // ── Detect program metadata from title page ───────────────────────────
        ProgramMeta meta = detectProgramMeta(pages.get(0), programNameOverride, batchStartYearOverride);
        System.out.println("Parsed: " + meta);

        // ── Get or create Program ─────────────────────────────────────────────
        Program program = programRepository.findByName(meta.programName)
                .orElseGet(() -> programRepository.save(newProgram(meta.programName)));

        // ── Get or create Specialization ──────────────────────────────────────
        Specialization spec = null;
        if (meta.specializationName != null) {
            final Program fp = program;
            spec = specializationRepository.findByNameAndProgram(meta.specializationName, program)
                    .orElseGet(() -> {
                        Specialization s = new Specialization();
                        s.setName(meta.specializationName);
                        s.setProgram(fp);
                        return specializationRepository.save(s);
                    });
        }

        // ── Get or create Batch ────────────────────────────────────────────────
        final Specialization fspec = spec;
        final Program fprog = program;
        Batch batch = batchRepository.findByStartYearAndProgramAndSpecialization(
                meta.startYear, program, spec)
                .orElseGet(() -> {
                    Batch b = new Batch();
                    b.setStartYear(meta.startYear);
                    b.setEndYear(meta.endYear);
                    b.setProgram(fprog);
                    b.setSpecialization(fspec);
                    return batchRepository.save(b);
                });

        // ── Parse POs ─────────────────────────────────────────────────────────
        int poCount = parsePOs(fullText, program);

        // ── Parse PSOs ────────────────────────────────────────────────────────
        int psoCount = spec != null ? parsePSOs(fullText, spec) : 0;

        // ── Parse Semesters + Courses ─────────────────────────────────────────
        Map<Integer, List<String>> semesterCourses = parseSemesterCourses(pages, program, batch, spec);
        int courseCount = semesterCourses.values().stream().mapToInt(List::size).sum();

        // ── Parse CO descriptions per course ─────────────────────────────────
        int coCount = parseCOs(pages, program);

        // ── Parse CO-PO / CO-PSO mappings (Unified & Upserting) ───────────────
        int[] mappingsCount = parseMappings(pages, program, spec);
        int copoCount = mappingsCount[0];
        int copsoCount = mappingsCount[1];

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("program", meta.programName);
        result.put("specialization", meta.specializationName);
        result.put("batch", meta.startYear + "-" + meta.endYear);
        result.put("semesters_found", semesterCourses.size());
        result.put("courses_found", courseCount);
        result.put("pos_found", poCount);
        result.put("psos_found", psoCount);
        result.put("cos_found", coCount);
        result.put("copo_mappings", copoCount);
        result.put("copso_mappings", copsoCount);
        result.put("semester_breakdown", semesterCourses.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        e -> "Semester " + e.getKey(),
                        e -> e.getValue(),
                        (a, b) -> a, LinkedHashMap::new)));
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DETECT PROGRAM METADATA FROM TITLE PAGE
    // ─────────────────────────────────────────────────────────────────────────
    private ProgramMeta detectProgramMeta(String titlePage, String override, Integer yearOverride) {
        ProgramMeta meta = new ProgramMeta();

        // ── Program name ──────────────────────────────────────────────────────
        if (override != null && !override.isBlank()) {
            meta.programName = override.trim();
        } else {
            meta.programName = detectProgramName(titlePage);
        }

        // ── Specialization ────────────────────────────────────────────────────
        meta.specializationName = detectSpecialization(titlePage);

        // ── Batch years ───────────────────────────────────────────────────────
        if (yearOverride != null) {
            meta.startYear = yearOverride;
            meta.endYear = yearOverride + detectProgramDuration(meta.programName);
        } else {
            int[] years = detectBatchYears(titlePage, meta.programName);
            meta.startYear = years[0];
            meta.endYear = years[1];
        }

        return meta;
    }

    private String detectProgramName(String text) {
        String t = text.toLowerCase();
        if (t.contains("m.tech") || t.contains("m. tech") || t.contains("master of technology"))
            return "MTech";
        if (t.contains("m.c.a") || t.contains("master of computer application"))
            return "MCA";
        if (t.contains("b.tech") || t.contains("b. tech") || t.contains("bachelor of technology"))
            return "BTech";
        if (t.contains("b.c.a") || t.contains("bca") || t.contains("bachelor in computer application"))
            return "BCA";
        if (t.contains("b.sc") || t.contains("b. sc") || t.contains("bachelor of science"))
            return "BSc";
        return "Unknown";
    }

    private String detectSpecialization(String text) {
        // "Specialization in AI & Data Science" or "Specialization in Cyber Security"
        Matcher m = Pattern.compile(
                "(?:Specialization|Specialisation)\\s+(?:in)?\\s+([A-Za-z &/()]+?)\\s*(?:\\[|\\(|\\n|Programme|Program|with|2\\d{3})",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            String s = m.group(1).trim();
            // Clean up
            s = s.replaceAll("(?i)(Honours|Hons|Research|\\[).*", "").trim();
            if (!s.isBlank() && s.length() > 2)
                return s;
        }
        // Fallback: look for common specialization names
        String t = text;
        if (t.contains("AI & Data Science") || t.contains("AI and Data Science"))
            return "AI & Data Science";
        if (t.contains("Cyber Security"))
            return "Cyber Security";
        if (t.contains("Data Science"))
            return "Data Science";
        if (t.contains("Computer Science"))
            return "Computer Science";
        if (t.contains("Full Stack") || t.contains("FSD"))
            return "Full Stack Development";
        if (t.contains("UI/UX") || t.contains("UI UX"))
            return "UI/UX";
        return null;
    }

    private int[] detectBatchYears(String text, String programName) {
        // "2023-24" → 2023, 2026 (3yr BCA) or 2027 (4yr BTech)
        // "2023-26" → 2023, 2026
        Matcher m = Pattern.compile("(20\\d{2})-(\\d{2,4})").matcher(text);
        if (m.find()) {
            int start = Integer.parseInt(m.group(1));
            String endStr = m.group(2);
            int end = endStr.length() == 2
                    ? Integer.parseInt("20" + endStr)
                    : Integer.parseInt(endStr);
            // If end < start (e.g. "2023-24" → end=2024 < realistic end)
            // use program duration to compute proper end year
            if (end <= start)
                end = start + detectProgramDuration(programName);
            return new int[] { start, end };
        }
        int start = 2023; // default
        return new int[] { start, start + detectProgramDuration(programName) };
    }

    private int detectProgramDuration(String name) {
        if (name == null)
            return 3;
        switch (name) {
            case "BTech":
            case "BCA":
                return 4; // 4 years = 8 semesters... but BCA is 3yr
            case "MTech":
            case "MCA":
                return 2;
            default:
                return 3; // BSc, BCA default 3yr
        }
        // Actually BCA is 3yr, BTech is 4yr - let's be more precise
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PO PARSER
    // ─────────────────────────────────────────────────────────────────────────
    private int parsePOs(String fullText, Program program) {
        Set<String> seen = new HashSet<>();
        int count = 0;

        // Pattern: "PO1. Description..." or "PO1 Description..."
        // Also handling tabs or lots of spaces, and descriptions that cross newlines
        Pattern p = Pattern.compile(
                "(PO\\s*\\d+)[\\s.:\\-]+([A-Z][A-Za-z0-9\\s.,;:'\"/()-]{10,400})",
                Pattern.MULTILINE);
        Matcher m = p.matcher(fullText);

        while (m.find()) {
            String code = m.group(1).replaceAll("\\s+", "").trim();
            String desc = m.group(2).trim()
                    .replaceAll("\\s+", " ")
                    .replaceAll("^[-:.]\\s*", "");

            if (seen.contains(code) || desc.isBlank() || desc.length() < 5 || desc.contains("Total"))
                continue;
            seen.add(code);

            final String fcode = code, fdesc = desc;
            final Program fp = program;
            poRepository.findByCodeAndProgram(code, program).ifPresentOrElse(
                    po -> {
                        if (po.getDescription() == null || po.getDescription().length() < 10) {
                            po.setDescription(fdesc);
                            poRepository.save(po);
                        }
                    },
                    () -> {
                        PO po = new PO();
                        po.setCode(fcode);
                        po.setDescription(fdesc);
                        po.setProgram(fp);
                        poRepository.save(po);
                    });
            count++;
        }
        // Fallback: If no POs found, generate standard 12 generic POs (since OBE
        // usually strictly requires 12)
        if (count == 0) {
            for (int i = 1; i <= 12; i++) {
                String c = "PO" + i;
                if (poRepository.findByCodeAndProgram(c, program).isEmpty()) {
                    PO po = new PO();
                    po.setCode(c);
                    po.setDescription("Standard PO " + i);
                    po.setProgram(program);
                    poRepository.save(po);
                    count++;
                }
            }
        }
        System.out.println("POs parsed: " + count);
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PSO PARSER
    // ─────────────────────────────────────────────────────────────────────────
    private int parsePSOs(String fullText, Specialization spec) {
        Set<String> seen = new HashSet<>();
        int count = 0;

        Pattern p = Pattern.compile(
                "(PSO\\s*\\d+)[\\s.:\\-]+([A-Z][A-Za-z0-9\\s.,;:'\"/()-]{10,400})",
                Pattern.MULTILINE);
        Matcher m = p.matcher(fullText);

        while (m.find()) {
            String code = m.group(1).replaceAll("\\s+", "").trim();
            String desc = m.group(2).trim().replaceAll("\\s+", " ");

            if (seen.contains(code) || desc.isBlank() || desc.contains("Total"))
                continue;
            seen.add(code);

            final String fcode = code, fdesc = desc;
            final Specialization fs = spec;
            psoRepository.findByCodeAndProgram(code, spec.getProgram()).ifPresentOrElse(
                    pso -> {
                        if (pso.getDescription() == null || pso.getDescription().length() < 10) {
                            pso.setDescription(fdesc);
                            psoRepository.save(pso);
                        }
                    },
                    () -> {
                        PSO pso = new PSO();
                        pso.setCode(fcode);
                        pso.setDescription(fdesc);
                        pso.setProgram(fs.getProgram());
                        psoRepository.save(pso);
                    });
            count++;
        }
        System.out.println("PSOs parsed: " + count);
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEMESTER + COURSE PARSER
    // ─────────────────────────────────────────────────────────────────────────
    private Map<Integer, List<String>> parseSemesterCourses(
            List<String> pages, Program program, Batch batch, Specialization spec) {

        Map<Integer, List<String>> result = new LinkedHashMap<>();
        // Semester pattern — longest first to avoid I matching II/III etc
        Pattern semPat = Pattern.compile(
                "(?:Semester|SEMESTER)\\s*[-–:]?\\s*(VIII|VII|VI|IV|V|III|II|I)",
                Pattern.CASE_INSENSITIVE);
        // Course code pattern — covers ENBC101, ENCA201, ENBT301, ENMA103 etc
        Pattern codePat = Pattern.compile(
                "(EN[A-Z]{2}\\d{3}|[A-Z]{2,4}\\d{3,4})\\s+([A-Z][A-Za-z0-9 ,/&()\\-]+?)\\s{2,}");

        int currentSem = -1;
        Set<String> processedSems = new HashSet<>();

        for (String page : pages) {
            Matcher sm = semPat.matcher(page);
            if (sm.find()) {
                int semNum = romanToInt(sm.group(1));
                if (!processedSems.contains(String.valueOf(semNum))) {
                    currentSem = semNum;
                    processedSems.add(String.valueOf(semNum));
                }
            }

            if (currentSem < 1)
                continue;

            // Find course rows on this page
            Matcher cm = codePat.matcher(page);
            final int sem = currentSem;
            final Batch fb = batch;
            final Specialization fs = spec;
            final Program fp = program;

            while (cm.find()) {
                String code = cm.group(1).trim();
                String name = cm.group(2).trim().replaceAll("\\s+", " ");
                if (name.length() < 3 || name.toUpperCase().contains("TOTAL"))
                    continue;

                // Get or create semester
                Semester semester = semesterRepository.findByNumberAndBatch(sem, fb)
                        .orElseGet(() -> {
                            Semester s = new Semester();
                            s.setNumber(sem);
                            s.setBatch(fb);
                            return semesterRepository.save(s);
                        });

                // Get or create course
                courseRepository.findByCourseCodeAndBatch(code, fb).orElseGet(() -> {
                    Course c = new Course();
                    c.setCourseCode(code);
                    c.setCourseName(name);
                    c.setProgram(fp);
                    c.setSemester(semester);
                    c.setBatch(fb);
                    c.setSpecialization(fs);
                    return courseRepository.save(c);
                });

                result.computeIfAbsent(sem, k -> new ArrayList<>()).add(code);
            }
        }
        System.out.println("Semester-course map: " + result.keySet());
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CO PARSER — parse CO1..CO6 descriptions per course page
    // ─────────────────────────────────────────────────────────────────────────
    private int parseCOs(List<String> pages, Program program) {
        // Course code pattern at top of course detail page
        Pattern courseCodePat = Pattern.compile("(EN[A-Z]{2}\\d{3}|[A-Z]{2,4}\\d{3,4})");
        // CO pattern: "CO1: description" or "CO1 description" or "CO1. description"
        Pattern coPat = Pattern.compile("(CO\\d+)[.:\\s]+([^\\n\\r]{10,300})");
        int count = 0;

        for (String page : pages) {
            if (!page.contains("CO1") && !page.contains("CO2"))
                continue;

            // Find course code on this page
            Matcher ccm = courseCodePat.matcher(page);
            if (!ccm.find())
                continue;
            String code = ccm.group(1);

            Course course = courseRepository.findFirstByCourseCode(code).orElse(null);
            if (course == null)
                continue;

            // Parse COs
            Matcher cm = coPat.matcher(page);
            while (cm.find()) {
                String coCode = cm.group(1).trim();
                String desc = cm.group(2).trim().replaceAll("\\s+", " ");
                if (desc.isBlank() || desc.length() < 5)
                    continue;

                final Course fc = course;
                final String fc2 = code + "-" + coCode; // "ENBC101-CO1"
                final String fd = desc;

                coRepository.findByCodeAndCourse(coCode, course).ifPresentOrElse(
                        co -> {
                            if (co.getDescription() == null || co.getDescription().startsWith("CO")) {
                                co.setDescription(fd);
                                coRepository.save(co);
                            }
                        },
                        () -> {
                            CO co = new CO();
                            co.setCode(fc2);
                            co.setDescription(fd);
                            co.setCourse(fc);
                            coRepository.save(co);
                        });
                count++;
            }
        }
        System.out.println("COs parsed: " + count);
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UNIFIED MAPPING PARSER
    // Handles multi-courses per page, combined PO+PSO tables, and UPSERTS
    // ─────────────────────────────────────────────────────────────────────────
    private int[] parseMappings(List<String> pages, Program program, Specialization spec) {
        List<PO> pos = poRepository.findByProgram(program);
        pos.sort(Comparator.comparingInt(p -> {
            try { return Integer.parseInt(p.getCode().replaceAll("\\D+", "")); } 
            catch (Exception e) { return 999; }
        }));
        List<PSO> psos = spec != null ? psoRepository.findByProgram(spec.getProgram()) : new ArrayList<>();
        psos.removeIf(p -> p.getCode().toUpperCase().contains("CODE"));
        psos.sort(Comparator.comparingInt(p -> {
            try { return Integer.parseInt(p.getCode().replaceAll("\\D+", "")); } 
            catch (Exception e) { return 999; }
        }));
        
        int poCount = pos.size();
        int psoCount = psos.size();
        if (poCount == 0) return new int[]{0, 0};
        
        int copo = 0, copso = 0;
        Pattern combinedPat = Pattern.compile("(?i)(EN[A-Z]{2}\\d{3}|[A-Z]{2,4}\\d{3,4})|(CO\\d+)\\s+([0-3\\-\\s]{4,})");
        
        for (String page : pages) {
            if (!page.contains("CO1") || (!page.contains("PO1") && !page.contains("PSO1"))) continue;
            
            Matcher m = combinedPat.matcher(page);
            Course currentCourse = null;
            List<CO> currentCOs = null;
            
            while (m.find()) {
                if (m.group(1) != null) {
                    Course course = courseRepository.findFirstByCourseCode(m.group(1).toUpperCase()).orElse(null);
                    if (course != null) {
                        currentCourse = course;
                        currentCOs = new ArrayList<>(coRepository.findByCourse(currentCourse));
                    }
                } else if (m.group(2) != null) {
                    if (currentCourse == null) continue;
                    
                    String coCode = m.group(2).toUpperCase();
                    CO co = currentCOs != null ? currentCOs.stream().filter(c -> c.getCode().endsWith(coCode)).findFirst().orElse(null) : null;
                    if (co == null) {
                        co = new CO();
                        co.setCode(currentCourse.getCourseCode() + "-" + coCode);
                        co.setDescription("Auto-generated " + coCode);
                        co.setCourse(currentCourse);
                        co = coRepository.save(co);
                        if (currentCOs == null) currentCOs = new ArrayList<>();
                        currentCOs.add(co);
                    }
                    
                    final CO finalCo = co;
                    String[] vals = m.group(3).trim().replaceAll("\\s+", " ").split(" ");
                    
                    // Unified logic: if table row has ~12-16 items, we match sequentially.
                    boolean hasPsoOnly = vals.length <= 6 && page.contains("PSO"); // Likely a separate PSO-only table
                    
                    for (int i = 0; i < vals.length; i++) {
                        if (vals[i].equals("-") || vals[i].isBlank()) continue;
                        try {
                            int w = Integer.parseInt(vals[i]);
                            if (w < 1 || w > 3) continue;
                            
                            if (hasPsoOnly) {
                                if (i < psoCount) {
                                    PSO pso = psos.get(i);
                                    COPSOMapping map = copsoRepository.findByCo(finalCo).stream()
                                        .filter(x -> x.getPso().getId().equals(pso.getId()))
                                        .findFirst().orElseGet(() -> {
                                            COPSOMapping n = new COPSOMapping(); n.setCo(finalCo); n.setPso(pso); return n;
                                        });
                                    if (map.getWeight() != w) { map.setWeight(w); copsoRepository.save(map); copso++; }
                                }
                            } else {
                                if (i < poCount) {
                                    PO po = pos.get(i);
                                    COPOMap map = copoRepository.findByCo(finalCo).stream()
                                        .filter(x -> x.getPo().getId().equals(po.getId()))
                                        .findFirst().orElseGet(() -> {
                                            COPOMap n = new COPOMap(); n.setCo(finalCo); n.setPo(po); return n;
                                        });
                                    if (map.getWeight() != w) { map.setWeight(w); copoRepository.save(map); copo++; }
                                } else if (i - poCount < psoCount) {
                                    PSO pso = psos.get(i - poCount);
                                    COPSOMapping map = copsoRepository.findByCo(finalCo).stream()
                                        .filter(x -> x.getPso().getId().equals(pso.getId()))
                                        .findFirst().orElseGet(() -> {
                                            COPSOMapping n = new COPSOMapping(); n.setCo(finalCo); n.setPso(pso); return n;
                                        });
                                    if (map.getWeight() != w) { map.setWeight(w); copsoRepository.save(map); copso++; }
                                }
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        System.out.println("CO-PO mappings upserted: " + copo + ", CO-PSO mappings upserted: " + copso);
        return new int[]{copo, copso};
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private int romanToInt(String r) {
        if (r == null)
            return 1;
        switch (r.toUpperCase().trim()) {
            case "VIII":
                return 8;
            case "VII":
                return 7;
            case "VI":
                return 6;
            case "IV":
                return 4;
            case "V":
                return 5;
            case "III":
                return 3;
            case "II":
                return 2;
            default:
                return 1;
        }
    }

    private Program newProgram(String name) {
        Program p = new Program();
        p.setName(name);
        return p;
    }

    private static class ProgramMeta {
        String programName, specializationName;
        int startYear, endYear;

        public String toString() {
            return programName + " / " + specializationName + " [" + startYear + "-" + endYear + "]";
        }
    }
}
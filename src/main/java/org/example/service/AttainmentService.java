package org.example.service;

import org.example.entity.*;
import org.example.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class AttainmentService {

    private static final double ATTAINMENT_THRESHOLD = 40.0;  // 40% threshold for CO scoring
    // CO Level thresholds (minimum level is always 1 — Level 0 does NOT exist):
    //  >= 80% of students passing -> Level 3
    //  >= 60% of students passing -> Level 2
    //  anything else              -> Level 1 (including < 40%)
    private static final double LEVEL2_THRESHOLD = 60.0;
    private static final double LEVEL3_THRESHOLD = 80.0;
    private static final double PO_SCALE = 3.0;  // CO level scale 0-3

    private final StudentMarkRepository studentMarkRepository;
    private final QuestionCOMappingRepository questionCOMappingRepository;
    private final CO_PO_MappingRepository coPoMappingRepository;
    private final COPSORepository coPsoMappingRepository;
    private final CORepository coRepository;
    private final CourseRepository courseRepository;
    private final org.example.repository.SpecializationRepository specializationRepository;
    private final org.example.util.EnrollmentCodeUtil enrollmentCodeUtil;

    // Static map and getEnrollmentCodesForSpecId() removed.
    // Use enrollmentCodeUtil.getEnrollmentCodesForSpecId() instead (single source of truth).

    /**
     * Calculate overall CO attainment for a course (combines mid-term and end-term).
     *
     * Special rule for end-term: Questions 2, 3, 4, 5 have (a) and (b) options.
     * For each such question group, we take the MAXIMUM of (a) and (b) marks per student
     * (student attempts one option). The max mark for the group = max(a_max, b_max).
     *
     * CO Attainment Level:
     *   Level 1 : > 40% of students scored >= 40% of CO marks
     *   Level 2 : > 60% of students scored >= 40% of CO marks
     *   Level 3 : > 80% of students scored >= 40% of CO marks
     *   Level 0 : <= 40% of students scored >= 40% of CO marks
     *
     * Returns: Map<coCode, level>  where level is 0, 1, 2, or 3.
     */
    public Map<String, Double> calculateCOAttainment(Long courseId) {
        return calculateCOAttainment(courseId, null);
    }

    /**
     * Calculate CO attainment scoped to a specific specialization.
     * When specializationId is non-null, only marks from students with that
     * specialization_id are included — giving per-specialization attainment.
     */
    public Map<String, Double> calculateCOAttainment(Long courseId, Long specializationId) {
        List<StudentMark> marks;
        if (specializationId != null) {
            marks = studentMarkRepository.findByCourseIdAndSpecId(courseId, specializationId);
        } else {
            marks = studentMarkRepository.findByCourseId(courseId);
        }
        List<QuestionCOMapping> mappings = questionCOMappingRepository.findByCourseId(courseId);

        if (marks.isEmpty() || mappings.isEmpty()) {
            return new HashMap<>();
        }

        // ── Step 1: separate marks by exam type ───────────────────────────────────
        List<StudentMark> midTermMarks = new ArrayList<>();
        List<StudentMark> endTermMarks = new ArrayList<>();
        for (StudentMark m : marks) {
            if ("end_term".equalsIgnoreCase(m.getExamType())) {
                endTermMarks.add(m);
            } else {
                midTermMarks.add(m);
            }
        }

        // ── Step 2: build coMaxMarks from QuestionCOMapping ───────────────────────
        // For end-term alternative questions (Q2a/Q2b, Q3a/Q3b, Q4a/Q4b, Q5a/Q5b)
        // the max marks for the CO contribution of that group = max(a_max, b_max).
        // We compute this separately for end-term.
        Map<Long, Double> coMaxMarks = new HashMap<>();

        // Mid-term: straightforward sum of all mapped question maxMarks
        for (QuestionCOMapping q : mappings) {
            // Only count mid-term relevant mappings (all non-alternative questions)
            if (!isEndTermAlternativeQuestion(q.getQuestionLabel())) {
                Long coId = q.getCo().getId();
                coMaxMarks.merge(coId, q.getMaxMarks(), Double::sum);
            }
        }

        // End-term: for Q2-Q5, keep only the higher maxMarks of (a) vs (b) per CO
        // Group: questionBase (e.g. "Q2") -> coId -> max of a_max, b_max
        Map<String, Map<Long, Double>> endTermBaseMaxPerCO = new HashMap<>();
        for (QuestionCOMapping q : mappings) {
            if (isEndTermAlternativeQuestion(q.getQuestionLabel())) {
                String base = getAlternativeQuestionBase(q.getQuestionLabel()); // e.g. "Q2"
                Long coId = q.getCo().getId();
                endTermBaseMaxPerCO
                    .computeIfAbsent(base, k -> new HashMap<>())
                    .merge(coId, q.getMaxMarks(), Double::max);
            }
        }
        // Add end-term alternative max marks into coMaxMarks
        for (Map<Long, Double> coMaxMap : endTermBaseMaxPerCO.values()) {
            for (Map.Entry<Long, Double> e : coMaxMap.entrySet()) {
                coMaxMarks.merge(e.getKey(), e.getValue(), Double::sum);
            }
        }

        // Also add end-term non-alternative question maxMarks (unlikely but safe)
        // (Our mappings may include e.g. Q1 in end-term which is not alternative)
        // We rely on the fact that mid-term questions are separate label-space from end-term
        // so duplicate label collision is OK as-is.

        // ── Step 3: aggregate student marks per CO ────────────────────────────────
        // Map: studentId -> coId -> achieved marks
        Map<Long, Map<Long, Double>> studentCOMarks = new HashMap<>();

        // Mid-term marks: straightforward sum
        for (StudentMark mark : midTermMarks) {
            Optional<QuestionCOMapping> map = mappings.stream()
                    .filter(m -> m.getQuestionLabel().equalsIgnoreCase(mark.getQuestion()))
                    .findFirst();
            if (map.isPresent()) {
                Long coId = map.get().getCo().getId();
                Long studentId = mark.getStudent().getId();
                studentCOMarks.computeIfAbsent(studentId, k -> new HashMap<>())
                        .merge(coId, mark.getMarks(), Double::sum);
            }
        }

        // End-term marks: for Q2-Q5 alternatives, take max(a, b) per question-base per student
        // Map: studentId -> questionBase -> (coId, maxMarksObtained)
        Map<Long, Map<String, double[]>> endTermAltBest = new HashMap<>(); // [0]=marks, [1]=maxPossible
        List<StudentMark> endTermNonAlt = new ArrayList<>();

        for (StudentMark mark : endTermMarks) {
            if (isEndTermAlternativeQuestion(mark.getQuestion())) {
                String base = getAlternativeQuestionBase(mark.getQuestion());
                Long studentId = mark.getStudent().getId();
                // Find the CO for this specific question
                Optional<QuestionCOMapping> map = mappings.stream()
                        .filter(m -> m.getQuestionLabel().equalsIgnoreCase(mark.getQuestion()))
                        .findFirst();
                if (map.isPresent()) {
                    Long coId = map.get().getCo().getId();
                    // key: base + "|" + coId so different COs on same base are tracked separately
                    String key = base + "|" + coId;
                    endTermAltBest.computeIfAbsent(studentId, k -> new HashMap<>())
                            .merge(key, new double[]{mark.getMarks(), mark.getMaxMarks()},
                                    (old, neu) -> old[0] >= neu[0] ? old : neu);
                }
            } else {
                endTermNonAlt.add(mark);
            }
        }

        // Add best-option marks to studentCOMarks
        for (Map.Entry<Long, Map<String, double[]>> studentEntry : endTermAltBest.entrySet()) {
            Long studentId = studentEntry.getKey();
            for (Map.Entry<String, double[]> entry : studentEntry.getValue().entrySet()) {
                String key = entry.getKey(); // "Q2|coId"
                Long coId = Long.parseLong(key.substring(key.indexOf('|') + 1));
                double achieved = entry.getValue()[0];
                studentCOMarks.computeIfAbsent(studentId, k -> new HashMap<>())
                        .merge(coId, achieved, Double::sum);
            }
        }

        // End-term non-alternative marks: straightforward sum
        for (StudentMark mark : endTermNonAlt) {
            Optional<QuestionCOMapping> map = mappings.stream()
                    .filter(m -> m.getQuestionLabel().equalsIgnoreCase(mark.getQuestion()))
                    .findFirst();
            if (map.isPresent()) {
                Long coId = map.get().getCo().getId();
                Long studentId = mark.getStudent().getId();
                studentCOMarks.computeIfAbsent(studentId, k -> new HashMap<>())
                        .merge(coId, mark.getMarks(), Double::sum);
            }
        }

        // ── Step 4: calculate % students >= 40%, then assign level ───────────────
        Map<String, Double> coAttainment = new HashMap<>();
        Set<Long> allStudents = studentCOMarks.keySet();
        int totalStudents = allStudents.size();

        for (Long coId : coMaxMarks.keySet()) {
            int studentsAboveThreshold = 0;
            double maxForCO = coMaxMarks.get(coId);
            if (maxForCO <= 0) continue;

            for (Long studentId : allStudents) {
                double obtained = studentCOMarks.get(studentId).getOrDefault(coId, 0.0);
                double percentage = (obtained / maxForCO) * 100.0;
                if (percentage >= ATTAINMENT_THRESHOLD) {
                    studentsAboveThreshold++;
                }
            }

            // Return the raw passing percentage (0-100%) for frontend CO bar display.
            // PO/PSO methods use percentToLevel() to convert this to a level (0-3).
            double passingPercent = totalStudents > 0
                    ? ((double) studentsAboveThreshold / totalStudents) * 100.0
                    : 0.0;

            CO co = coRepository.findById(coId).orElse(null);
            String coCode = co != null ? co.getCode() : "CO" + coId;
            coAttainment.put(coCode, Math.round(passingPercent * 10.0) / 10.0);
        }

        // ── Deduplicate: prefer full-code (ENCS101-CO1) over short-code (CO1) ─────
        // When both formats exist (legacy + new ingestion), remove the short form.
        Map<String, Double> deduped = new LinkedHashMap<>();
        // Collect all suffix→fullCode so we can detect duplicates
        Map<String, String> suffixToFullCode = new LinkedHashMap<>();
        for (String code : coAttainment.keySet()) {
            String suffix = extractCOSuffix(code);
            String existing = suffixToFullCode.get(suffix);
            // Prefer the longer code (full code wins over short code)
            if (existing == null || code.length() > existing.length()) {
                suffixToFullCode.put(suffix, code);
            }
        }
        // Build final deduped map using only the winning code per suffix
        for (Map.Entry<String, String> e : suffixToFullCode.entrySet()) {
            String winCode = e.getValue();
            deduped.put(winCode, coAttainment.get(winCode));
        }
        // Sort by CO number
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(deduped.entrySet());
        sorted.sort(Comparator.comparingInt(e -> {
            try { return Integer.parseInt(extractCOSuffix(e.getKey()).replaceAll("\\D+", "")); }
            catch (Exception ex) { return 999; }
        }));
        Map<String, Double> result2 = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : sorted) result2.put(e.getKey(), e.getValue());
        return result2;
    }

    /**
     * Converts a CO passing-percentage (0-100) to a level (1, 2, or 3).
     * Minimum level is always 1 — Level 0 does NOT exist.
     *   >= 80% of students passing threshold -> Level 3
     *   >= 60%                               -> Level 2
     *   anything else (including < 40%)      -> Level 1
     */
    private double percentToLevel(double passingPercent) {
        if (passingPercent >= LEVEL3_THRESHOLD) return 3.0;
        if (passingPercent >= LEVEL2_THRESHOLD) return 2.0;
        return 1.0; // minimum level — even if < 40% pass, CO still contributes Level 1
    }

    /**
     * Get CO attainment levels (1-3) for each CO in a course.
     * Level 1: Fewer than 60% students achieved >= 40% of CO marks (minimum level, never 0)
     * Level 2: >= 60% students achieved >= 40% of CO marks
     * Level 3: >= 80% students achieved >= 40% of CO marks
     *
     * @param courseId Course ID
     * @return Map of CO code -> level (1-3)
     */
    public Map<String, Integer> getCOLevels(Long courseId) {
        return getCOLevels(courseId, null);
    }

    /** CO levels scoped to a specialization. */
    public Map<String, Integer> getCOLevels(Long courseId, Long specializationId) {
        Map<String, Double> coAttainments = calculateCOAttainment(courseId, specializationId);
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : coAttainments.entrySet()) {
            levels.put(entry.getKey(), (int) percentToLevel(entry.getValue()));
        }
        return levels;
    }

    /** Distinct student count for a course (all students). */
    public int getStudentCount(Long courseId) {
        List<StudentMark> marks = studentMarkRepository.findByCourseId(courseId);
        return (int) marks.stream().map(m -> m.getStudent().getId()).distinct().count();
    }

    /** Distinct student count for a course scoped to a specialization. */
    public int getStudentCountBySpec(Long courseId, Long specializationId) {
        List<StudentMark> marks = studentMarkRepository.findByCourseIdAndSpecId(courseId, specializationId);
        return (int) marks.stream().map(m -> m.getStudent().getId()).distinct().count();
    }

    /**
     * Count of at-risk students (< 40% total marks) for a course,
     * optionally scoped to a specialization.
     */
    public int getAtRiskCount(Long courseId, Long specializationId) {
        List<StudentMark> marks;
        if (specializationId != null) {
            marks = studentMarkRepository.findByCourseIdAndSpecId(courseId, specializationId);
        } else {
            marks = studentMarkRepository.findByCourseId(courseId);
        }
        Map<Long, List<StudentMark>> byStudent = new java.util.HashMap<>();
        for (StudentMark sm : marks) {
            byStudent.computeIfAbsent(sm.getStudent().getId(), k -> new ArrayList<>()).add(sm);
        }
        int atRisk = 0;
        for (List<StudentMark> sms : byStudent.values()) {
            double got = sms.stream().filter(sm -> sm.getMaxMarks() != null && sm.getMaxMarks() > 0)
                    .mapToDouble(sm -> sm.getMarks() != null ? sm.getMarks() : 0).sum();
            double max = sms.stream().filter(sm -> sm.getMaxMarks() != null && sm.getMaxMarks() > 0)
                    .mapToDouble(StudentMark::getMaxMarks).sum();
            if (max > 0 && (got / max) < 0.40) atRisk++;
        }
        return atRisk;
    }

    /**
     * Returns true if the question label is an end-term alternative question
     * (Q2a, Q2b, Q3a, Q3b, Q4a, Q4b, Q5a, Q5b — case-insensitive).
     */
    private boolean isEndTermAlternativeQuestion(String label) {
        if (label == null) return false;
        return label.matches("(?i)Q[2-5][a-bA-B].*");
    }

    /**
     * Returns the base question identifier for alternative questions.
     * E.g. "Q2(a)" -> "Q2", "Q3b" -> "Q3".
     */
    private String getAlternativeQuestionBase(String label) {
        if (label == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)(Q[2-5])").matcher(label);
        return m.find() ? m.group(1).toUpperCase() : label;
    }

    /**
     * Calculate PO attainment based on CO-PO mappings
     * PO attainment = weighted average of CO attainments (0-3 scale)
     */
    public Map<String, Double> calculatePOAttainment(Long courseId) {
        return calculatePOAttainment(courseId, null);
    }

    public Map<String, Double> calculatePOAttainment(Long courseId, Long specializationId) {
        Map<String, Double> coAttainments = calculateCOAttainment(courseId, specializationId);

        if (coAttainments.isEmpty()) {
            System.out.println("[PO_DEBUG] ⚠️  CO Attainments is EMPTY - returning empty map");
            return new HashMap<>();
        }

        System.out.println("[PO_DEBUG] Finding CO-PO mappings for course: " + courseId);
        List<COPOMap> mappings = coPoMappingRepository.findByCoCourseId(courseId);
        
        // DEBUG: Log mapping status
        System.out.println("[PO_DEBUG] ✓ CO-PO Mappings found: " + mappings.size());
        System.out.println("[PO_DEBUG] CO Attainments: " + coAttainments);

        if (mappings.isEmpty()) {
            System.out.println("[PO_DEBUG] ⚠️  WARNING: No CO-PO mappings found for course " + courseId);
            return new HashMap<>();
        }
        System.out.println("[PO_DEBUG] ✓ Processing " + mappings.size() + " CO-PO mappings...");

        Map<String, Double> poScores = new HashMap<>();
        Map<String, Integer> poWeights = new HashMap<>();

        for (COPOMap map : mappings) {
            if (map.getPo() == null || map.getCo() == null) {
                System.out.println("[PO_ATTAINMENT_DEBUG] WARNING: Null PO or CO in mapping");
                continue;
            }
            
            String poCode = map.getPo().getCode();
            String coCode = map.getCo().getCode();
            
            // Handle CO code mismatch: try exact match first, then fallback to suffix match
            double coAttainmentPercent = coAttainments.getOrDefault(coCode, 0.0);
            
            // If exact match fails (e.g., "ENSP201-CO1" vs "CO1"), try to match by CO suffix
            if (coAttainmentPercent == 0.0 && !coAttainments.containsKey(coCode)) {
                // Extract CO suffix (e.g., "CO1" from "ENSP201-CO1")
                String coSuffix = extractCOSuffix(coCode);
                if (coSuffix != null) {
                    for (String attainmentCode : coAttainments.keySet()) {
                        String attainmentSuffix = extractCOSuffix(attainmentCode);
                        if (coSuffix.equals(attainmentSuffix)) {
                            coAttainmentPercent = coAttainments.get(attainmentCode);
                            System.out.println("[PO_ATTAINMENT_DEBUG] CO code matched: " + coCode + " -> " + attainmentCode);
                            break;
                        }
                    }
                }
            }

            // coAttainmentPercent is passing % (0-100); convert to level 0-3 for PO formula
            double coLevel = percentToLevel(coAttainmentPercent);

            int weight = map.getWeight();
            // Formula: PO = Σ(coLevel * weight) / Σweight  --> 0-3 decimal
            poScores.merge(poCode, coLevel * weight, Double::sum);
            poWeights.merge(poCode, weight, Integer::sum);

            System.out.println("[PO_ATTAINMENT_DEBUG] Mapped CO: " + coCode + " to PO: " + poCode +
                    " (weight: " + weight + ", passingPct: " + coAttainmentPercent + ", coLevel: " + coLevel + ")");
        }

        Map<String, Double> poAttainment = new HashMap<>();
        for (String po : poScores.keySet()) {
            int totalWeight = poWeights.get(po);
            // Weighted average of levels → 0-3 decimal, rounded to 2dp
            double weightedScore = poScores.get(po) / totalWeight;
            poAttainment.put(po, Math.round(weightedScore * 100.0) / 100.0);
        }
        
        System.out.println("[PO_DEBUG] ✓ Calculated " + poAttainment.size() + " PO values");
        System.out.println("[PO_DEBUG] Final PO Attainments (0-3 scale): " + poAttainment);
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║        [PO_ATTAINMENT] PO calculation COMPLETE              ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        return poAttainment;
    }

    /**
     * Calculate PSO attainment based on CO-PSO mappings
     * PSO attainment = weighted average of CO attainments (0-3 scale)
     */
    public Map<String, Double> calculatePSOAttainment(Long courseId) {
        return calculatePSOAttainment(courseId, null);
    }

    public Map<String, Double> calculatePSOAttainment(Long courseId, Long specializationId) {
        Map<String, Double> coAttainments = calculateCOAttainment(courseId, specializationId);

        if (coAttainments.isEmpty()) {
            System.out.println("[PSO_DEBUG] ⚠️  CO Attainments is EMPTY - returning empty map");
            return new HashMap<>();
        }

        Course course = courseRepository.findById(courseId).orElse(null);
        if (course == null || course.getProgram() == null) {
            System.out.println("[PSO_DEBUG] ⚠️  Course " + courseId + " not found or has no program - returning empty");
            return new HashMap<>();
        }

        System.out.println("[PSO_DEBUG] ✓ Course: " + course.getCourseCode() + ", Program: " + course.getProgram().getName());
        System.out.println("[PSO_DEBUG] Finding COs for course: " + courseId);

        List<CO> coursesCOs = coRepository.findByCourseId(courseId);
        System.out.println("[PSO_DEBUG] ✓ COs found for course: " + coursesCOs.size());
        System.out.println("[PSO_DEBUG] Processing " + coursesCOs.size() + " COs for PSO mapping...");

        Map<String, Double> psoScores = new HashMap<>();
        Map<String, Integer> psoWeights = new HashMap<>();
        int totalMappings = 0;

        for (CO co : coursesCOs) {
            String coCode = co.getCode();
            
            // Handle CO code mismatch: try exact match first, then fallback to suffix match
            double coAttainmentPercent = coAttainments.getOrDefault(coCode, 0.0);
            
            // If exact match fails (e.g., "ENSP201-CO1" vs "CO1"), try to match by CO suffix
            if (coAttainmentPercent == 0.0 && !coAttainments.containsKey(coCode)) {
                // Extract CO suffix (e.g., "CO1" from "ENSP201-CO1")
                String coSuffix = extractCOSuffix(coCode);
                if (coSuffix != null) {
                    for (String attainmentCode : coAttainments.keySet()) {
                        String attainmentSuffix = extractCOSuffix(attainmentCode);
                        if (coSuffix.equals(attainmentSuffix)) {
                            coAttainmentPercent = coAttainments.get(attainmentCode);
                            System.out.println("[PSO_ATTAINMENT_DEBUG] CO code matched: " + coCode + " -> " + attainmentCode);
                            break;
                        }
                    }
                }
            }
            
            // coAttainmentPercent is passing % (0-100); convert to level 0-3 for PSO formula
            double coLevel = percentToLevel(coAttainmentPercent);

            List<COPSOMapping> psoMappings = coPsoMappingRepository.findByCoId(co.getId());
            System.out.println("[PSO_ATTAINMENT_DEBUG] CO " + coCode + " (ID: " + co.getId() + ") has " + psoMappings.size() + " PSO mappings");

            for (COPSOMapping mapping : psoMappings) {
                if (mapping.getPso() == null) {
                    System.out.println("[PSO_ATTAINMENT_DEBUG] WARNING: Null PSO in mapping for CO " + coCode);
                    continue;
                }

                String psoCode = mapping.getPso().getCode();
                int weight = mapping.getWeight();

                // Formula: PSO = Σ(coLevel * weight) / Σweight  --> 0-3 decimal
                psoScores.merge(psoCode, coLevel * weight, Double::sum);
                psoWeights.merge(psoCode, weight, Integer::sum);
                totalMappings++;

                System.out.println("[PSO_ATTAINMENT_DEBUG] Mapped CO: " + coCode + " to PSO: " + psoCode +
                        " (weight: " + weight + ", passingPct: " + coAttainmentPercent + ", coLevel: " + coLevel + ")");
            }
        }

        System.out.println("[PSO_ATTAINMENT_DEBUG] Total CO-PSO mappings processed: " + totalMappings);

        if (totalMappings == 0) {
            System.out.println("[PSO_DEBUG] ⚠️  WARNING: No CO-PSO mappings found for course " + courseId);
        }

        Map<String, Double> psoAttainment = new HashMap<>();
        for (String pso : psoScores.keySet()) {
            int totalWeight = psoWeights.get(pso);
            // Weighted average of levels → 0-3 decimal, rounded to 2dp
            double weightedScore = psoScores.get(pso) / totalWeight;
            psoAttainment.put(pso, Math.round(weightedScore * 100.0) / 100.0);
        }

        System.out.println("[PSO_DEBUG] ✓ Calculated " + psoAttainment.size() + " PSO values");
        System.out.println("[PSO_DEBUG] Final PSO Attainments (0-3 scale): " + psoAttainment);
        System.out.println("╔══════════════════════════════════════════════════���═════════╗");
        System.out.println("║        [PSO_ATTAINMENT] PSO calculation COMPLETE            ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        return psoAttainment;
    }

    /**
     * Get comprehensive attainment report for a course
     * Includes: overall (combined mid+end), by exam type, and individual student data
     */
    public Map<String, Object> getAttainmentReport(Long courseId) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("course_id", courseId);
        
        // Overall attainment (combined mid-term + end-term)
        report.put("co_attainments_overall", calculateCOAttainment(courseId));
        report.put("po_attainments", calculatePOAttainment(courseId));
        report.put("pso_attainments", calculatePSOAttainment(courseId));
        
        // Breakdown by exam type
        report.put("co_attainments_by_exam_type", calculateCOAttainmentByExamType(courseId));
        
        // Individual student CO attainments (combined)
        report.put("individual_student_co_attainments", calculateIndividualStudentCOAttainment(courseId));
        
        // Configuration
        report.put("threshold_percent", ATTAINMENT_THRESHOLD);
        report.put("po_pso_scale", PO_SCALE);
        report.put("exam_config", new LinkedHashMap<String, Object>() {{
            put("mid_term_max_marks", 20.0);
            put("end_term_max_marks", 50.0);
            put("total_max_marks", 70.0);
        }});
        
        return report;
    }

    /**
     * Calculate CO attainment by exam type (mid-term or end-term separately)
     */
    public Map<String, Map<String, Double>> calculateCOAttainmentByExamType(Long courseId) {
        List<StudentMark> marks = studentMarkRepository.findByCourseId(courseId);
        List<QuestionCOMapping> mappings = questionCOMappingRepository.findByCourseId(courseId);

        if (marks.isEmpty() || mappings.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Map<Long, Double>> coMaxMarksByType = new HashMap<>();
        Map<String, Map<Long, Map<Long, Double>>> studentCOMarksByType = new HashMap<>();

        // Initialize mid-term and end-term
        for (String examType : Arrays.asList("mid_term", "end_term")) {
            coMaxMarksByType.put(examType, new HashMap<>());
            studentCOMarksByType.put(examType, new HashMap<>());
        }

        // Build max marks per CO and exam type
        for (QuestionCOMapping q : mappings) {
            Long coId = q.getCo().getId();
            for (String examType : coMaxMarksByType.keySet()) {
                coMaxMarksByType.get(examType).putIfAbsent(coId, 0.0);
            }
        }

        // Aggregate marks per student per CO per exam type
        for (StudentMark mark : marks) {
            Optional<QuestionCOMapping> map = mappings.stream()
                    .filter(m -> m.getQuestionLabel().equalsIgnoreCase(mark.getQuestion()))
                    .findFirst();

            if (map.isPresent()) {
                Long coId = map.get().getCo().getId();
                Long studentId = mark.getStudent().getId();
                String examType = mark.getExamType();

                // Add to max marks
                coMaxMarksByType.get(examType).merge(coId, mark.getMaxMarks(), Double::sum);

                // Add to student marks
                studentCOMarksByType.get(examType)
                        .computeIfAbsent(studentId, k -> new HashMap<>())
                        .computeIfAbsent(coId, k -> 0.0);
                
                Map<Long, Double> studentCOs = studentCOMarksByType.get(examType).get(studentId);
                studentCOs.put(coId, studentCOs.getOrDefault(coId, 0.0) + mark.getMarks());
            }
        }

        // Calculate attainment per exam type
        Map<String, Map<String, Double>> attainmentByType = new LinkedHashMap<>();

        for (String examType : Arrays.asList("mid_term", "end_term")) {
            Map<String, Double> coAttainment = new HashMap<>();
            Map<Long, Double> maxMarks = coMaxMarksByType.get(examType);
            Map<Long, Map<Long, Double>> studentMarks = studentCOMarksByType.get(examType);

            if (maxMarks.isEmpty() || studentMarks.isEmpty()) continue;

            Set<Long> allStudents = studentMarks.keySet();
            int totalStudents = allStudents.size();

            for (Long coId : maxMarks.keySet()) {
                int studentsAboveThreshold = 0;
                double maxForCO = maxMarks.get(coId);

                for (Long studentId : allStudents) {
                    double obtained = studentMarks.get(studentId).getOrDefault(coId, 0.0);
                    double percentage = maxForCO > 0 ? (obtained / maxForCO) * 100.0 : 0.0;

                    if (percentage >= ATTAINMENT_THRESHOLD) {
                        studentsAboveThreshold++;
                    }
                }

                double attainment = totalStudents > 0 ? ((double) studentsAboveThreshold / totalStudents) * 100.0 : 0.0;
                CO co = coRepository.findById(coId).orElse(null);
                String coCode = co != null ? co.getCode() : "CO" + coId;
                coAttainment.put(coCode, attainment);
            }

            attainmentByType.put(examType, coAttainment);
        }

        return attainmentByType;
    }

    /**
     * Calculate individual student CO attainment (both mid+end term combined).
     * Uses a pre-built lookup map (O(1) per mark) instead of stream-filter (O(n) per mark).
     */
    public Map<Long, Map<String, Double>> calculateIndividualStudentCOAttainment(Long courseId) {
        // Use JOIN FETCH query to avoid lazy-loading student inside the loop
        List<StudentMark> marks = studentMarkRepository.findByCourseIdWithStudent(courseId);
        List<QuestionCOMapping> mappings = questionCOMappingRepository.findByCourseId(courseId);

        if (marks.isEmpty() || mappings.isEmpty()) {
            return new HashMap<>();
        }

        // ── Pre-load CO code map ONCE (avoids N+1: was doing coRepository.findById inside loop) ──
        List<CO> courseCoList = coRepository.findByCourseId(courseId);
        Map<Long, String> coIdToCode = new HashMap<>(courseCoList.size() * 2);
        for (CO co : courseCoList) {
            coIdToCode.put(co.getId(), co.getCode());
        }

        // ── Build O(1) lookup: UPPERCASE(questionLabel) → QuestionCOMapping ──────
        Map<String, QuestionCOMapping> labelToMapping = new HashMap<>();
        for (QuestionCOMapping q : mappings) {
            if (q.getQuestionLabel() != null) {
                labelToMapping.put(q.getQuestionLabel().toUpperCase(), q);
            }
        }

        // ── Build coMaxMarksGlobal: coId → total max marks ─────────────────────
        Map<Long, Double> coMaxMarksGlobal = new HashMap<>();
        for (QuestionCOMapping q : mappings) {
            coMaxMarksGlobal.merge(q.getCo().getId(), q.getMaxMarks(), Double::sum);
        }

        // Map: studentId → coId → total marks obtained
        Map<Long, Map<Long, Double>> studentCOMarks     = new HashMap<>();
        // Map: studentId → coId → max possible for that student
        Map<Long, Map<Long, Double>> coMaxMarksByStudent = new HashMap<>();

        // Aggregate per student using O(1) lookup (no DB calls inside this loop)
        for (StudentMark mark : marks) {
            String qUpper = mark.getQuestion() != null ? mark.getQuestion().toUpperCase() : "";
            QuestionCOMapping mapping = labelToMapping.get(qUpper);
            if (mapping == null) continue;

            Long coId      = mapping.getCo().getId();
            Long studentId = mark.getStudent().getId();

            coMaxMarksByStudent.computeIfAbsent(studentId, k -> new HashMap<>())
                    .put(coId, coMaxMarksGlobal.getOrDefault(coId, 0.0));

            studentCOMarks.computeIfAbsent(studentId, k -> new HashMap<>())
                    .merge(coId, mark.getMarks(), Double::sum);
        }

        // Calculate per-student attainment percentages
        // No DB calls here — all CO data pre-loaded above
        Map<Long, Map<String, Double>> result = new LinkedHashMap<>();
        for (Long studentId : studentCOMarks.keySet()) {
            Map<String, Double> studentAttainment = new HashMap<>();
            Map<Long, Double> studentMarks = studentCOMarks.get(studentId);
            Map<Long, Double> maxMarks     = coMaxMarksByStudent.get(studentId);

            for (Long coId : studentMarks.keySet()) {
                double obtained   = studentMarks.get(coId);
                double max        = maxMarks.getOrDefault(coId, 1.0);
                double percentage = max > 0 ? (obtained / max) * 100.0 : 0.0;

                // Look up coCode from pre-loaded map — no DB query
                String coCode = coIdToCode.getOrDefault(coId, "CO" + coId);
                studentAttainment.put(coCode, percentage);
            }
            result.put(studentId, studentAttainment);
        }

        return result;
    }


    /**
     * Get CO-PO mapping matrix for display (weights for each CO-PO pair)
     */
    public List<Map<String, Object>> getCOPOMappingMatrix(Long courseId) {
        List<COPOMap> mappings = coPoMappingRepository.findByCoCourseId(courseId);
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();

        // Build matrix: CO code -> PO code -> weight
        for (COPOMap mapping : mappings) {
            String coCode = mapping.getCo().getCode();
            String poCode = mapping.getPo().getCode();
            int weight = mapping.getWeight();

            matrix.computeIfAbsent(coCode, k -> new LinkedHashMap<>()).put(poCode, weight);
        }

        // Convert to list of maps for JSON serialization
        List<Map<String, Object>> result = new ArrayList<>();
        for (String coCode : matrix.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("co", coCode);
            row.putAll(matrix.get(coCode));
            result.add(row);
        }

        return result;
    }

    /**
     * Get CO-PSO mapping matrix for display (weights for each CO-PSO pair)
     */
    public List<Map<String, Object>> getCOPSOMappingMatrix(Long courseId) {
        List<CO> cos = coRepository.findByCourseId(courseId);
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();

        // Build matrix: CO code -> PSO code -> weight
        for (CO co : cos) {
            String coCode = co.getCode();
            List<COPSOMapping> psoMappings = coPsoMappingRepository.findByCoId(co.getId());

            for (COPSOMapping mapping : psoMappings) {
                String psoCode = mapping.getPso().getCode();
                int weight = mapping.getWeight();
                matrix.computeIfAbsent(coCode, k -> new LinkedHashMap<>()).put(psoCode, weight);
            }
        }

        // Convert to list of maps for JSON serialization
        List<Map<String, Object>> result = new ArrayList<>();
        for (String coCode : matrix.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("co", coCode);
            row.putAll(matrix.get(coCode));
            result.add(row);
        }

        return result;
    }

    /**
     * Extract CO suffix from a CO code string.
     * Handles both formats:
     * - "CO1" -> "CO1"
     * - "ENSP201-CO1" -> "CO1"
     * - "ENCS205-CO3" -> "CO3"
     */
    private String extractCOSuffix(String coCode) {
        if (coCode == null) return null;
        // Match "CO" followed by one or more digits at the end of the string
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(CO\\d+)$").matcher(coCode);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // Fallback: if the code itself looks like "CO1", "CO2", etc.
        if (coCode.matches("CO\\d+")) {
            return coCode;
        }
        return coCode; // return as-is if no pattern found
    }
}

package org.example.util;

import org.example.entity.Specialization;
import org.example.repository.SpecializationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Single source of truth for the enrollment-number → specialization mapping.
 *
 * Enrollment number format: YYPPSSNNNN
 *   YY   = batch year suffix  (e.g. "23" for 2023)
 *   PP   = program segment    (e.g. "01" for most BTech/BCA/BSc)
 *   SS   = specialization code (digits at Java index 4-5, SQL positions 5-6)
 *   NNNN = sequence number
 *
 * The mapping is populated from a single static block here and shared with
 * StudentSpecializationController, OBEDashboardController, and AttainmentService
 * via Spring injection — no duplication, no hard-coding in multiple classes.
 */
@Component
public class EnrollmentCodeUtil {

    /**
     * Maps the 2-digit enrollment spec-code (digits 4-5) → canonical specialization name.
     * null value = plain program with no named sub-specialization (plain CSE, plain BCA, plain BSc CS).
     *
     * This is the ONLY place this mapping lives in the application.
     */
    private static final Map<String, String> CODE_TO_SPEC_NAME = new LinkedHashMap<>();
    static {
        CODE_TO_SPEC_NAME.put("01", null);   // plain BTech CSE
        CODE_TO_SPEC_NAME.put("10", "Artificial Intelligence and Machine Learning");
        CODE_TO_SPEC_NAME.put("11", "Artificial Intelligence and Machine Learning");
        CODE_TO_SPEC_NAME.put("12", "Artificial Intelligence and Machine Learning");
        CODE_TO_SPEC_NAME.put("17", "Full Stack Development");
        CODE_TO_SPEC_NAME.put("18", "Full Stack Development");
        CODE_TO_SPEC_NAME.put("19", "Data Science");
        CODE_TO_SPEC_NAME.put("40", "Cyber Security");
        CODE_TO_SPEC_NAME.put("41", "Cyber Security");
        CODE_TO_SPEC_NAME.put("42", "UX/UI");
        CODE_TO_SPEC_NAME.put("20", null);   // plain BCA
        CODE_TO_SPEC_NAME.put("21", "Artificial Intelligence and Data Science");
        CODE_TO_SPEC_NAME.put("73", null);   // plain BSc CS
        CODE_TO_SPEC_NAME.put("83", "Cyber Security");
        CODE_TO_SPEC_NAME.put("84", "Data Science");
    }

    @Autowired
    private SpecializationRepository specializationRepository;

    /** Returns the full code → specName map (unmodifiable). Used by StudentSpecializationController. */
    public Map<String, String> getCodeToSpecNameMap() {
        return Collections.unmodifiableMap(CODE_TO_SPEC_NAME);
    }

    /**
     * Given a specialization entity ID, return the 2-digit enrollment codes
     * that correspond to it, derived by matching the specialization's name
     * against the CODE_TO_SPEC_NAME map.
     *
     * Returns an empty list for plain programs (no named sub-specialization)
     * or unknown specializations.
     *
     * Example: specId for "Data Science" → ["19", "84"]
     *          specId for "Full Stack Development" → ["17", "18"]
     *          specId for plain CSE → []
     */
    public List<String> getEnrollmentCodesForSpecId(Long specId) {
        if (specId == null) return List.of();
        Specialization spec = specializationRepository.findById(specId).orElse(null);
        if (spec == null || spec.getName() == null) return List.of();
        return getEnrollmentCodesForSpecName(spec.getName());
    }

    /**
     * Given a specialization name (from the DB), return the matching enrollment codes.
     *
     * Uses a two-stage approach:
     *  1. Normalized substring match (handles most exact/partial names).
     *  2. Keyword-pattern fallback — handles DB names that are abbreviations of the
     *     canonical names stored in CODE_TO_SPEC_NAME (e.g. "AI & ML" vs
     *     "Artificial Intelligence and Machine Learning", "UI / UX" vs "UX/UI").
     *     The keyword patterns are derived directly from the canonical spec names,
     *     not independently hardcoded.
     */
    public List<String> getEnrollmentCodesForSpecName(String specName) {
        if (specName == null || specName.isBlank()) return List.of();
        String nameLower = specName.trim().toLowerCase();

        // Stage 1: collect codes whose canonical name matches via substring containment
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> e : CODE_TO_SPEC_NAME.entrySet()) {
            String mappedName = e.getValue();
            if (mappedName == null) continue;
            String mappedLower = mappedName.toLowerCase();
            if (nameLower.contains(mappedLower) || mappedLower.contains(nameLower)) {
                if (!result.contains(e.getKey())) result.add(e.getKey());
            }
        }
        if (!result.isEmpty()) return Collections.unmodifiableList(result);

        // Stage 2: keyword-pattern fallback for abbreviated spec names.
        // Each pattern is derived from the corresponding canonical name in CODE_TO_SPEC_NAME.
        // Helper: check if all given keywords appear anywhere in nameLower.
        java.util.function.BiFunction<String, String[], Boolean> allContained = (n, words) -> {
            for (String w : words) if (!n.contains(w)) return false;
            return true;
        };

        // "AI & ML" / "AI and ML" / "Artificial Intelligence..." → codes 10,11,12
        if ((nameLower.contains("ai") && nameLower.contains("ml"))
                || allContained.apply(nameLower, new String[]{"artificial", "machine"})) {
            return codesForCanonicalName("Artificial Intelligence and Machine Learning");
        }
        // "Full Stack" / "FSD" → codes 17,18
        if (nameLower.contains("full") && nameLower.contains("stack")) {
            return codesForCanonicalName("Full Stack Development");
        }
        // "Cyber" / "Cyber Security" → codes 40,41,83
        if (nameLower.contains("cyber")) {
            return codesForCanonicalName("Cyber Security");
        }
        // "UI / UX" / "UX/UI" / "UX" / "UI Design" — exclude data science to avoid false match
        if ((nameLower.contains("ui") || nameLower.contains("ux"))
                && !nameLower.contains("data")) {
            return codesForCanonicalName("UX/UI");
        }
        // "AI & Data Science" (BCA) — check before plain "Data Science"
        if ((nameLower.contains("ai") || nameLower.contains("artificial"))
                && nameLower.contains("data")) {
            return codesForCanonicalName("Artificial Intelligence and Data Science");
        }
        // "Data Science" / "DS" → codes 19,84
        if (nameLower.contains("data") && (nameLower.contains("science") || nameLower.contains("sc"))) {
            return codesForCanonicalName("Data Science");
        }

        return List.of(); // plain program or unrecognised spec — no enrollment code restriction
    }

    /** Collect all codes whose canonical spec name equals the given canonical name. */
    private List<String> codesForCanonicalName(String canonicalName) {
        String lower = canonicalName.toLowerCase();
        List<String> codes = new ArrayList<>();
        for (Map.Entry<String, String> e : CODE_TO_SPEC_NAME.entrySet()) {
            if (e.getValue() != null && e.getValue().toLowerCase().equals(lower)) {
                codes.add(e.getKey());
            }
        }
        return Collections.unmodifiableList(codes);
    }

    /**
     * Extracts the 2-digit specialization code from an enrollment number.
     * Returns null if the number is too short.
     */
    public String extractSpecCode(String enrollmentNumber) {
        if (enrollmentNumber == null || enrollmentNumber.length() < 6) return null;
        return enrollmentNumber.substring(4, 6);
    }

    /**
     * Extracts the 2-digit year prefix from an enrollment number (digits 0-1).
     * E.g. "2301010172" → "23"
     */
    public String extractYearPrefix(String enrollmentNumber) {
        if (enrollmentNumber == null || enrollmentNumber.length() < 2) return null;
        return enrollmentNumber.substring(0, 2);
    }

    /**
     * Returns the canonical spec name for a given 2-digit enrollment code,
     * or null if no match (plain program).
     */
    public String getSpecNameForCode(String code) {
        return CODE_TO_SPEC_NAME.getOrDefault(code, null);
    }

    /**
     * Returns true if the given enrollment number belongs to a plain (un-specialized)
     * program segment (e.g. plain CSE code "01", plain BCA "20", plain BSc CS "73").
     */
    public boolean isPlainProgram(String enrollmentNumber) {
        String code = extractSpecCode(enrollmentNumber);
        if (code == null) return true;
        if (!CODE_TO_SPEC_NAME.containsKey(code)) return true;
        return CODE_TO_SPEC_NAME.get(code) == null;
    }
}

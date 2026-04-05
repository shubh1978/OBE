package org.example.service;

import org.example.entity.*;
import org.example.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class QuestionCOMappingUploadService {

    private final QuestionCOMappingRepository questionCOMappingRepository;
    private final CourseRepository courseRepository;
    private final CORepository coRepository;

    public Map<String, Object> uploadQuestionCOMapping(MultipartFile file) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int successCount = 0;

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            if (sheet == null) {
                throw new Exception("No sheet found in Excel file");
            }

            // Parse header row (0-based)
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new Exception("Header row not found");
            }

            // Find column indices
            int courseCodeCol = -1, questionCol = -1, coCodeCol = -1, maxMarksCol = -1;

            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String header = getCellString(headerRow.getCell(c)).toLowerCase().trim();
                if (header.contains("course")) courseCodeCol = c;
                else if (header.contains("question")) questionCol = c;
                else if (header.contains("co")) coCodeCol = c;
                else if (header.contains("max") || header.contains("marks")) maxMarksCol = c;
            }

            if (courseCodeCol == -1 || questionCol == -1 || coCodeCol == -1) {
                throw new Exception("Missing required columns: Course, Question, CO Code");
            }

            // Cache for performance
            Map<String, Course> courseCache = new HashMap<>();
            Map<String, CO> coCache = new HashMap<>();

            // Process data rows
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                try {
                    String courseCode = getCellString(row.getCell(courseCodeCol)).trim();
                    String questionLabel = getCellString(row.getCell(questionCol)).trim();
                    String coCode = getCellString(row.getCell(coCodeCol)).trim();
                    double maxMarks = maxMarksCol >= 0 ? getCellNumeric(row.getCell(maxMarksCol)) : 5.0;

                    if (courseCode.isEmpty() || questionLabel.isEmpty() || coCode.isEmpty()) {
                        warnings.add("Row " + (r + 1) + ": Skipped - missing required data");
                        continue;
                    }

                    // Get or fetch Course
                    Course course = courseCache.computeIfAbsent(courseCode, code ->
                        courseRepository.findByCourseCode(code).orElse(null)
                    );

                    if (course == null) {
                        errors.add("Row " + (r + 1) + ": Course not found: " + courseCode);
                        continue;
                    }

                    // Get or fetch CO
                    String coKey = coCode + "_" + course.getId();
                    CO co = coCache.computeIfAbsent(coKey, key ->
                        coRepository.findByCodeAndCourse(coCode, course).orElse(null)
                    );

                    if (co == null) {
                        errors.add("Row " + (r + 1) + ": CO not found: " + coCode + " for course " + courseCode);
                        continue;
                    }

                    // Check if mapping already exists
                    QuestionCOMapping existing = questionCOMappingRepository.findAll().stream()
                        .filter(m -> m.getCourse().getId().equals(course.getId()) &&
                                   m.getQuestionLabel().equalsIgnoreCase(questionLabel) &&
                                   m.getCo().getId().equals(co.getId()))
                        .findFirst()
                        .orElse(null);

                    if (existing != null) {
                        warnings.add("Row " + (r + 1) + ": Mapping already exists: " + questionLabel + " → " + coCode);
                        continue;
                    }

                    // Create and save mapping
                    QuestionCOMapping mapping = new QuestionCOMapping();
                    mapping.setCourse(course);
                    mapping.setQuestionLabel(questionLabel);
                    mapping.setCo(co);
                    mapping.setMaxMarks(maxMarks);

                    questionCOMappingRepository.save(mapping);
                    successCount++;

                } catch (Exception e) {
                    errors.add("Row " + (r + 1) + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("message", e.getMessage());
            return result;
        }

        result.put("status", successCount > 0 ? "SUCCESS" : "PARTIAL");
        result.put("message", "Question-CO mapping upload completed");
        result.put("mappings_created", successCount);
        result.put("errors_count", errors.size());
        result.put("warnings_count", warnings.size());

        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        if (!warnings.isEmpty()) {
            result.put("warnings", warnings);
        }

        return result;
    }

    private String getCellString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            default: return "";
        }
    }

    private double getCellNumeric(Cell cell) {
        if (cell == null) return 0.0;
        switch (cell.getCellType()) {
            case NUMERIC: return cell.getNumericCellValue();
            case STRING:
                try {
                    return Double.parseDouble(cell.getStringCellValue());
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            default: return 0.0;
        }
    }
}


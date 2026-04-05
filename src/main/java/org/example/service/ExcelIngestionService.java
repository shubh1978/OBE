package org.example.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.entity.CO;
import org.example.entity.ExcelUploadResult;
import org.example.repository.CORepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ExcelIngestionService {

    private final CORepository coRepository;

    /**
     * EXPECTED TEMPLATE:
     * Sheet 1: Metadata (ignored for now)
     * Sheet 2: Question-CO Mapping | Columns: Question, CO_CODE, MaxMarks
     * Sheet 3: Student Marks | Columns: StudentId, Q1, Q2, Q3...
     */
    public ExcelUploadResult ingest(Long courseId, MultipartFile file) {
        ExcelUploadResult result = new ExcelUploadResult();
        try (InputStream is = file.getInputStream(); Workbook wb = new XSSFWorkbook(is)) {
            Sheet qCoSheet = wb.getSheetAt(1);
            Sheet marksSheet = wb.getSheetAt(2);

            Map<Integer, CO> questionIndexToCO = new HashMap<>();
            Map<Integer, Double> questionIndexToMax = new HashMap<>();

            // Parse Question-CO mapping
            for (int r = 1; r <= qCoSheet.getLastRowNum(); r++) {
                Row row = qCoSheet.getRow(r);
                if (row == null) continue;
                int qIndex = r; // aligns with marks columns
                String coCode = row.getCell(1).getStringCellValue();
                double max = row.getCell(2).getNumericCellValue();

                CO co = coRepository.findByCourseId(courseId).stream()
                        .filter(c -> c.getCode().equalsIgnoreCase(coCode))
                        .findFirst().orElse(null);
                if (co != null) {
                    questionIndexToCO.put(qIndex, co);
                    questionIndexToMax.put(qIndex, max);
                }
            }

            int students = 0;
            for (int r = 1; r <= marksSheet.getLastRowNum(); r++) {
                Row row = marksSheet.getRow(r);
                if (row == null) continue;
                students++;
                // Here we would accumulate marks per CO (next stage)
            }

            result.setSuccess(true);
            result.setStudentsProcessed(students);
        } catch (Exception e) {
            result.getErrors().add(e.getMessage());
            result.setSuccess(false);
        }
        return result;
    }
}
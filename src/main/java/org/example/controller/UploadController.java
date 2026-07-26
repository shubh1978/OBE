package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.service.CoPOMappingExcelService;
import org.example.service.ExcelStructureParserService;
import org.example.service.HandbookParserService;
import org.example.service.QuestionWiseReportIngestionService;
import org.example.service.ZipIngestionService;
import org.example.service.ResultExcelIngestionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class UploadController {

    private final ZipIngestionService                 zipIngestionService;
    private final HandbookParserService               handbookParserService;
    private final ExcelStructureParserService         excelStructureParserService;
    private final CoPOMappingExcelService             coPOMappingExcelService;
    private final ResultExcelIngestionService         resultExcelIngestionService;
    private final QuestionWiseReportIngestionService  questionWiseReportIngestionService;

    /**
     * Upload mark sheets ZIP
     *
     * curl -X POST http://localhost:8080/marks/upload-zip \
     *      -F "file=@/path/to/marks.zip"
     */
    @PostMapping(value = "/marks/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadMarksZip(
            @RequestPart("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(zipIngestionService.ingestZip(file));
    }

    /**
     * POST /marks/upload-single-excel
     * Upload a single question-wise marks entry report Excel (.xlsx) directly
     * without needing to ZIP it first.
     *
     * Accepted file format: same EntrySheet format as the SOET mark sheets
     *   e.g. "ETCS206A COMPUTER GRAPHICS (mid term).xlsx"
     *        "ETCS211A OPERATING SYSTEMS (end term).xlsx"
     *
     * Exam type is auto-detected from the filename:
     *   "(mid term)" or "mid_term" in filename → mid_term
     *   "(end term)" or "end_term" in filename → end_term
     *
     * ✅ LOCAL  : hits localhost:8080
     * 🚀 PROD   : same endpoint, no changes needed
     *
     * curl -X POST http://localhost:8080/marks/upload-single-excel \
     *      -F "file=@'ETCS206A COMPUTER GRAPHICS (mid term).xlsx'"
     */
    @PostMapping(value = "/marks/upload-single-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadSingleExcel(
            @RequestPart("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(zipIngestionService.ingestSingleExcel(file));
    }

    /**
     * POST /marks/upload-qwise-report
     * Upload the university "Question wise marks entry report" Excel.
     * Format: Sheet2 with columns — Roll no, Course Code, Class Name, Component Name,
     *         Frequency No, Q1..Q24.
     *
     * Component → exam type mapping:
     *   contains "Class Test"  + Frequency 1 → mid_term  (max 20)
     *   contains "End Term"               → end_term (max 50)
     *   "Practical Internal/External"     → skipped
     *   Frequency 2 of Class Test         → skipped (dedup prevention)
     *
     * Safe to re-upload — duplicate rows are never created.
     *
     * ✅ LOCAL  : hits localhost:8080
     * 🚀 PROD   : same endpoint, no changes needed
     *
     * curl -X POST http://localhost:8080/marks/upload-qwise-report \
     *      -F "file=@'Question wise marks entry report Odd sem.xlsx'"
     */
    @PostMapping(value = "/marks/upload-qwise-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadQwiseReport(
            @RequestPart("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(questionWiseReportIngestionService.ingest(file));
    }

    /**
     * POST /marks/upload-result-excel
     * Upload the university Result Excel (e.g. "Result Odd Sem 2025-26.xlsx").
     * Reads Sheet2 which has one row per student-course with:
     *   Internal Marks (col 14)  → mid_term,  maxMarks=20
     *   External Marks (col 15)  → end_term,  maxMarks=50
     * AB (absent) rows are skipped. Duplicate rows are safely ignored on re-upload.
     *
     * ✅ LOCAL  : hits localhost:8080 via frontend LOCAL_MODE=true
     * 🚀 PROD   : same endpoint, deployed on Render
     *
     * curl -X POST http://localhost:8080/marks/upload-result-excel \
     *      -F "file=@'Result Odd Sem 2025-26.xlsx'"
     */
    @PostMapping(value = "/marks/upload-result-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadResultExcel(
            @RequestPart("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(resultExcelIngestionService.ingest(file));
    }

    /**
     * Upload NBA handbook PDF (auto-detects program, batch, specialization)
     *
     * Basic upload (auto-detect everything):
     *   curl -X POST http://localhost:8080/handbook/upload \
     *        -F "file=@/path/to/BCA_Handbook.pdf"
     *
     * With overrides (when auto-detection is wrong):
     *   curl -X POST http://localhost:8080/handbook/upload \
     *        -F "file=@/path/to/BCA_Handbook.pdf" \
     *        -F "programName=BCA" \
     *        -F "batchYear=2023"
     *
     * programName options: BCA | BTech | BSc | MCA | MTech
     * batchYear: 4-digit start year e.g. 2023
     */
    @PostMapping(value = "/handbook/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadHandbook(
            @RequestPart("file")                                        MultipartFile file,
            @RequestParam(value = "programName", required = false)      String programName,
            @RequestParam(value = "batchYear",   required = false)      Integer batchYear
    ) throws Exception {
        Map<String, Object> result = handbookParserService.parseHandbook(file, programName, batchYear);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /copomap/upload
     * Upload a "CO PO MAPPING SHEET" Excel file to create/update POs, PSOs,
     * courses, COs, CO-PO and CO-PSO mappings.
     *
     * Required params:
     *   programName      — e.g. "BSc", "BTech", "MCA"
     *   specializationName — e.g. "Computer Science", "Data Science"
     *   startYear        — batch start year, e.g. 2025
     *   endYear          — batch end year,   e.g. 2027
     *
     * Example:
     *   curl -X POST http://localhost:8080/copomap/upload \
     *        -F "file=@'CO PO MAPPING SHEET B.Sc.-CS 2025-2027.xlsx'" \
     *        -F "programName=BSc" \
     *        -F "specializationName=Computer Science" \
     *        -F "startYear=2025" \
     *        -F "endYear=2027"
     */
    @PostMapping(value = "/copomap/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadCoPOMapExcel(
            @RequestPart("file")                                              MultipartFile file,
            @RequestParam(value = "programName",       required = false)      String programName,
            @RequestParam(value = "specializationName",required = false)      String specializationName,
            @RequestParam(value = "startYear",         required = false)      Integer startYear,
            @RequestParam(value = "endYear",           required = false)      Integer endYear
    ) throws Exception {
        Map<String, Object> result = coPOMappingExcelService.parseExcel(
                file, programName, specializationName, startYear, endYear);
        return ResponseEntity.ok(result);
    }
    /**
     * POST /structure/upload
     * Upload the OBE_Structure_Template.xlsx to create all entities
     *
     * curl -X POST http://localhost:8080/structure/upload \
     *      -F "file=@/path/to/OBE_Structure_Template.xlsx"
     */
    @PostMapping(value = "/structure/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadStructure(
            @RequestPart("file") MultipartFile file) throws Exception {
        Map<String, Object> result = excelStructureParserService.parse(file);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /structure/verify
     * Upload the filled Excel template and compare with DB
     */
    @PostMapping(value = "/structure/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> verifyStructure(
            @RequestPart("file") MultipartFile file) throws Exception {
        Map<String, Object> result = excelStructureParserService.verify(file);
        return ResponseEntity.ok(result);
    }
}
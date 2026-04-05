package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.service.ExcelStructureParserService;
import org.example.service.HandbookParserService;
import org.example.service.ZipIngestionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class UploadController {

    private final ZipIngestionService         zipIngestionService;
    private final HandbookParserService       handbookParserService;
    private final ExcelStructureParserService excelStructureParserService;

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
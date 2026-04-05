package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.entity.ExcelUploadResult;
import org.example.service.ExcelIngestionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/excel")
@RequiredArgsConstructor
public class ExcelUploadController {

    private final ExcelIngestionService excelIngestionService;

    @PostMapping("/upload")
    public ExcelUploadResult upload(@RequestParam Long courseId,
                                    @RequestParam MultipartFile file) {
        return excelIngestionService.ingest(courseId, file);
    }
}


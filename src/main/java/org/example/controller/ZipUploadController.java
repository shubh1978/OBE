package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.service.ZipIngestionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ZipUploadController {

    private final ZipIngestionService zipIngestionService;

    @PostMapping(value = "/marks/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadMarksZip(
            @RequestPart("file") MultipartFile file) throws Exception {
        
        Map<String, Object> response = zipIngestionService.ingestZip(file);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public String upload(@RequestPart("file") MultipartFile file) throws Exception {
        zipIngestionService.ingestZip(file);
        return "ZIP INGESTED SUCCESSFULLY";
    }
}


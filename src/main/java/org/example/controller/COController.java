package org.example.controller;

import org.example.entity.CO;
import org.example.service.COService;
import org.example.service.QuestionCOMappingUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cos")
@RequiredArgsConstructor
public class COController {

    private final COService coService;
    private final QuestionCOMappingUploadService questionCOMappingUploadService;

    @PostMapping
    public CO createCO(@RequestParam Long courseId, @RequestBody CO co) {
        return coService.create(courseId, co);
    }

    @GetMapping("/by-course/{courseId}")
    public List<CO> getCOsByCourse(@PathVariable Long courseId) {
        return coService.getByCourse(courseId);
    }

    @PostMapping(value = "/bulk-upload-question-co-mapping", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadQuestionCOMapping(
            @RequestPart("file") MultipartFile file) throws Exception {
        Map<String, Object> result = questionCOMappingUploadService.uploadQuestionCOMapping(file);
        return ResponseEntity.ok(result);
    }
}


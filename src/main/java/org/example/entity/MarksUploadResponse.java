package org.example.entity;

import lombok.*;
import java.util.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class MarksUploadResponse {
    
    private String status;  // "SUCCESS", "PARTIAL", "FAILED"
    private String message;
    private int filesProcessed;
    private int filesSkipped;
    private long totalMarkRecordsSaved;
    private List<String> errors;
    private Map<String, Object> attainmentSummary;  // overall attainment data
    
    // Helper constructor
    public MarksUploadResponse(String status, String message, int filesProcessed, 
                               int filesSkipped, long totalRecords, List<String> errors) {
        this.status = status;
        this.message = message;
        this.filesProcessed = filesProcessed;
        this.filesSkipped = filesSkipped;
        this.totalMarkRecordsSaved = totalRecords;
        this.errors = errors;
        this.attainmentSummary = new LinkedHashMap<>();
    }
}


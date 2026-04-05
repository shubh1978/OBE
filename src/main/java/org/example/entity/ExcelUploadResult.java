package org.example.entity;

import lombok.*;
import java.util.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ExcelUploadResult {
    private boolean success;
    private List<String> errors = new ArrayList<>();
    private int studentsProcessed;
}

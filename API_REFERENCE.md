# API Endpoint Reference Guide

## Attainment Calculation Endpoints

### 1. Get CO (Course Outcome) Attainment
**Endpoint**: `GET /api/attainment/co/{courseId}`

**Description**: Get CO attainment for a specific course. Values range from 0-100% representing the percentage of students achieving >= 40% on combined mid-term and end-term exams.

**Parameters**:
- `courseId` (path): ID of the course

**Example Request**:
```bash
curl http://localhost:8080/api/attainment/co/1
```

**Example Response** (200 OK):
```json
{
  "CO1": 85.5,
  "CO2": 92.3,
  "CO3": 78.9
}
```

**What it means**:
- CO1: 85.5% of students achieved >= 40% on CO1 (combined mid+end)
- CO2: 92.3% of students achieved >= 40% on CO2
- etc.

---

### 2. Get CO Attainment by Exam Type
**Endpoint**: `GET /api/attainment/co-by-exam-type/{courseId}`

**Description**: Get separate CO attainment for mid-term and end-term exams.

**Example Request**:
```bash
curl http://localhost:8080/api/attainment/co-by-exam-type/1
```

**Example Response** (200 OK):
```json
{
  "mid_term": {
    "CO1": 78.5,
    "CO2": 88.2
  },
  "end_term": {
    "CO1": 92.0,
    "CO2": 96.5
  }
}
```

---

### 3. Get Individual Student CO Attainment
**Endpoint**: `GET /api/attainment/students/{courseId}`

**Description**: Get CO attainment percentage for each individual student (combined mid+end).

**Example Request**:
```bash
curl http://localhost:8080/api/attainment/students/1
```

**Example Response** (200 OK):
```json
{
  "1": {
    "CO1": 75.0,
    "CO2": 85.5,
    "CO3": 65.0
  },
  "2": {
    "CO1": 90.0,
    "CO2": 92.0,
    "CO3": 88.5
  }
}
```

**Key**: Student ID, **Value**: CO code → Percentage

---

### 4. Get PO (Programme Outcome) Attainment
**Endpoint**: `GET /api/attainment/po/{courseId}`

**Description**: Get PO attainment for a course. Values are on 0-3 scale (0-3 represents 0%-100%).

**Example Request**:
```bash
curl http://localhost:8080/api/attainment/po/1
```

**Example Response** (200 OK):
```json
{
  "PO1": 2.5,
  "PO2": 2.1,
  "PO3": 1.8
}
```

**What it means**:
- PO1: Score of 2.5/3.0 = 83.3% attainment
- PO2: Score of 2.1/3.0 = 70.0% attainment
- etc.

**Frontend Conversion**: `(score / 3) * 100` = percentage

---

### 5. Get PSO (Programme Specialization Outcome) Attainment
**Endpoint**: `GET /api/attainment/pso/{courseId}`

**Description**: Get PSO attainment for a course. Values are on 0-3 scale.

**Example Request**:
```bash
curl http://localhost:8080/api/attainment/pso/1
```

**Example Response** (200 OK):
```json
{
  "PSO1": 2.4,
  "PSO2": 1.9
}
```

---

### 6. Get Comprehensive Attainment Report
**Endpoint**: `GET /api/attainment/report/{courseId}`

**Description**: Get complete attainment report including CO (overall + by exam type + individual), PO, PSO, and configuration.

**Example Request**:
```bash
curl http://localhost:8080/api/attainment/report/1
```

**Example Response** (200 OK):
```json
{
  "course_id": 1,
  "co_attainments_overall": {
    "CO1": 85.5,
    "CO2": 92.3
  },
  "po_attainments": {
    "PO1": 2.5,
    "PO2": 2.1
  },
  "pso_attainments": {
    "PSO1": 2.4,
    "PSO2": 1.9
  },
  "co_attainments_by_exam_type": {
    "mid_term": {...},
    "end_term": {...}
  },
  "individual_student_co_attainments": {...},
  "threshold_percent": 40.0,
  "po_pso_scale": 3.0,
  "exam_config": {
    "mid_term_max_marks": 20.0,
    "end_term_max_marks": 50.0,
    "total_max_marks": 70.0
  }
}
```

---

## Diagnostic Endpoints

### 1. Debug Attainment Issues for a Course
**Endpoint**: `GET /api/diagnostics/attainment-debug/{courseId}`

**Description**: Comprehensive diagnostic info to identify why attainment is not calculating.

**Example Request**:
```bash
curl http://localhost:8080/api/diagnostics/attainment-debug/1
```

**Example Response**:
```json
{
  "course": "ENBC101",
  "total_student_marks": 450,
  "sample_mark": {
    "student": "2401830001",
    "question": "Q1(a)",
    "marks": 5.0,
    "max_marks": 10.0,
    "exam_type": "end_term"
  },
  "total_question_co_mappings": 12,
  "sample_mappings": [
    {
      "question_label": "Q1(a)",
      "co_code": "CO1",
      "max_marks": 10.0
    }
  ],
  "matching_analysis": {
    "questions_in_marks": ["Q1(a)", "Q1(b)", "Q2", ...],
    "questions_in_mappings": ["Q1(a)", "Q1(b)", "Q2", ...],
    "matched_questions": 12,
    "unmatched_mark_questions": 0,
    "unmatched_mapping_questions": 0
  }
}
```

---

### 2. Debug PO Mappings
**Endpoint**: `GET /api/diagnostics/po-mapping-debug/{courseId}`

**Description**: Check if CO-PO mappings exist and are configured correctly.

**Example Request**:
```bash
curl http://localhost:8080/api/diagnostics/po-mapping-debug/1
```

**Example Response**:
```json
{
  "course_code": "ENBC101",
  "program_id": 1,
  "program_name": "B.Tech",
  "total_pos_for_program": 5,
  "pos": [
    {
      "id": 1,
      "code": "PO1",
      "description": "Graduates will be able to..."
    }
  ],
  "total_co_po_mappings": 3,
  "co_po_mappings": [
    {
      "co_code": "CO1",
      "po_code": "PO1",
      "weight": 1
    }
  ],
  "total_cos": 3
}
```

**⚠️ If `total_co_po_mappings` is 0**: This is why PO attainment is 0%!

---

### 3. Debug PSO Mappings
**Endpoint**: `GET /api/diagnostics/pso-mapping-debug/{courseId}`

**Description**: Check if CO-PSO mappings exist and are configured correctly.

**Example Request**:
```bash
curl http://localhost:8080/api/diagnostics/pso-mapping-debug/1
```

**Example Response**:
```json
{
  "course_code": "ENBC101",
  "program_id": 1,
  "program_name": "B.Tech",
  "specialization_id": 1,
  "total_psos_for_program": 2,
  "psos": [
    {
      "id": 1,
      "code": "PSO1",
      "program_id": 1,
      "specialization_id": 1,
      "description": "Specialize in..."
    }
  ],
  "total_cos": 3,
  "total_co_pso_mappings": 2,
  "co_pso_mappings_by_co": {
    "CO1": [
      {
        "pso_code": "PSO1",
        "pso_id": 1,
        "weight": 1
      }
    ]
  }
}
```

**⚠️ If `total_co_pso_mappings` is 0**: This is why PSO attainment is 0%!

---

### 4. Get Mapping Summary for All Courses
**Endpoint**: `GET /api/diagnostics/mapping-summary`

**Description**: Overview of all courses and their mapping status.

**Example Request**:
```bash
curl http://localhost:8080/api/diagnostics/mapping-summary
```

**Example Response** (200 OK):
```json
[
  {
    "course_code": "ENBC101",
    "course_id": 1,
    "student_marks": 450,
    "question_co_mappings": 12,
    "status": "✅ OK"
  },
  {
    "course_code": "ENBC103",
    "course_id": 2,
    "student_marks": 380,
    "question_co_mappings": 10,
    "status": "✅ OK"
  },
  {
    "course_code": "ENBC105",
    "course_id": 3,
    "student_marks": 0,
    "question_co_mappings": 0,
    "status": "❌ No data"
  }
]
```

---

## Mapping Management Endpoints

### Create CO-PO Mapping
**Endpoint**: `POST /api/co-po-mapping`

**Description**: Create a mapping between a CO and PO with specified weight.

**Parameters**:
- `coId` (query): ID of the CO
- `poId` (query): ID of the PO
- `level` (query): Weight/Level (typically 1-5)

**Example Request**:
```bash
curl -X POST "http://localhost:8080/api/co-po-mapping?coId=1&poId=1&level=1"
```

**Example Response** (201 Created):
```json
{
  "id": 1,
  "co": {
    "id": 1,
    "code": "CO1",
    "course": {...}
  },
  "po": {
    "id": 1,
    "code": "PO1"
  },
  "weight": 1
}
```

---

### Create CO-PSO Mapping (if endpoint exists)
**Endpoint**: `POST /api/co-pso-mapping` (if implemented)

**Description**: Create a mapping between a CO and PSO with specified weight.

**Parameters**:
- `coId` (query): ID of the CO
- `psoId` (query): ID of the PSO
- `level` (query): Weight/Level

---

## Upload Endpoints

### Upload Marks ZIP
**Endpoint**: `POST /marks/upload-zip`

**Description**: Upload a ZIP file containing Excel files with student marks.

**Parameters**:
- `file` (multipart): ZIP file containing .xlsx files

**Example Request**:
```bash
curl -X POST http://localhost:8080/marks/upload-zip \
  -F "file=@marks.zip"
```

**Example Response** (200 OK):
```json
{
  "message": "Marks uploaded successfully",
  "status": "SUCCESS",
  "files_processed": 5,
  "files_skipped": 0,
  "total_mark_records_saved": 1250,
  "attainment_summary": {
    "ENBC101": {
      "co_attainments_overall": {...},
      "po_attainments": {...},
      "pso_attainments": {...}
    }
  }
}
```

---

## Troubleshooting Response Codes

| Code | Meaning | Solution |
|------|---------|----------|
| 200 | Success | All good! |
| 404 | Resource not found | Course ID doesn't exist |
| 500 | Server error | Check application logs |
| Empty response | No data | Run diagnostics endpoints |
| All zeros | No mappings | Create CO-PO/CO-PSO mappings |

---

## Quick Testing Workflow

1. **Check if data exists**:
   ```bash
   curl http://localhost:8080/api/diagnostics/mapping-summary
   ```

2. **Debug specific course** (if status is not OK):
   ```bash
   curl http://localhost:8080/api/diagnostics/attainment-debug/{courseId}
   ```

3. **Check PO mappings**:
   ```bash
   curl http://localhost:8080/api/diagnostics/po-mapping-debug/{courseId}
   ```

4. **Check PSO mappings**:
   ```bash
   curl http://localhost:8080/api/diagnostics/pso-mapping-debug/{courseId}
   ```

5. **Get attainment** (once mappings are created):
   ```bash
   curl http://localhost:8080/api/attainment/report/{courseId}
   ```


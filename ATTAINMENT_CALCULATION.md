# OBE Attainment Calculation — Complete Reference

This document explains how **Course Outcome (CO)**, **Program Outcome (PO)**, and **Program Specific Outcome (PSO)** attainment values are calculated in the OBE Analytics System.

> **Implementation file:** `src/main/java/org/example/service/AttainmentService.java`

---

## Table of Contents

1. [Key Concepts & Terminology](#1-key-concepts--terminology)
2. [Data Model (Entity Relationships)](#2-data-model-entity-relationships)
3. [Constants & Configuration](#3-constants--configuration)
4. [CO Attainment Calculation](#4-co-attainment-calculation)
5. [PO Attainment Calculation](#5-po-attainment-calculation)
6. [PSO Attainment Calculation](#6-pso-attainment-calculation)
7. [CO Attainment by Exam Type (Breakdown)](#7-co-attainment-by-exam-type-breakdown)
8. [Individual Student CO Attainment](#8-individual-student-co-attainment)
9. [Attainment Report (API Output)](#9-attainment-report-api-output)
10. [Worked Example](#10-worked-example)

---

## 1. Key Concepts & Terminology

| Term | Meaning |
|------|---------|
| **CO** (Course Outcome) | A specific learning outcome defined for a course (e.g., CO1, CO2...) |
| **PO** (Program Outcome) | A broad program-level outcome (e.g., PO1–PO12 for a BTech program) |
| **PSO** (Program Specific Outcome) | An outcome specific to a specialization (e.g., PSO1–PSO4 for CSE) |
| **CO-PO Mapping** | A weighted relationship linking a CO to a PO (`weight` = 1, 2, or 3) |
| **CO-PSO Mapping** | A weighted relationship linking a CO to a PSO (`weight` = 1, 2, or 3) |
| **QuestionCOMapping** | Maps an exam question (e.g., "Q1(a)") to a specific CO for a course |
| **StudentMark** | A single row storing one student's marks for one question in one exam |
| **Attainment Threshold** | The minimum percentage a student must score to be considered as "attaining" a CO |

---

## 2. Data Model (Entity Relationships)

```
┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│   Student    │      │    Course     │      │   Program    │
└──────┬───────┘      └──────┬───────┘      └──────┬───────┘
       │                     │                     │
       │                     │                     │
       ▼                     ▼                     ▼
┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│ StudentMark  │      │      CO      │      │      PO      │
│              │      │ (Course      │      │ (Program     │
│ - question   │      │  Outcome)    │      │  Outcome)    │
│ - marks      │      │              │      │              │
│ - maxMarks   │      │ - code       │      │ - code       │
│ - examType   │      │ - course_id  │      │ - program_id │
└──────────────┘      └──────┬───────┘      └──────────────┘
                             │                     ▲
                             │                     │
                     ┌───────┴────────┐    ┌───────┴────────┐
                     │                │    │                │
                     ▼                ▼    │                │
              ┌──────────────┐ ┌──────────────┐     ┌──────────────┐
              │ QuestionCO   │ │  CO-PO Map   │     │     PSO      │
              │ Mapping      │ │              │     │ (Program     │
              │              │ │ - co_id      │     │  Specific    │
              │ - question   │ │ - po_id      │     │  Outcome)    │
              │ - co_id      │ │ - weight     │     └──────▲───────┘
              │ - maxMarks   │ └──────────────┘            │
              │ - course_id  │                      ┌──────┴───────┐
              └──────────────┘                      │  CO-PSO Map  │
                                                    │              │
                                                    │ - co_id      │
                                                    │ - pso_id     │
                                                    │ - weight     │
                                                    └──────────────┘
```

### Key Entities

| Entity | Table | Key Fields |
|--------|-------|------------|
| `StudentMark` | `student_mark` | `student_id`, `course_id`, `question`, `marks`, `max_marks`, `exam_type` |
| `CO` | `co` | `id`, `code` (e.g., "CO1"), `course_id` |
| `QuestionCOMapping` | `questioncomapping` | `course_id`, `question_label`, `co_id`, `max_marks` |
| `COPOMap` | `co_po_mapping` | `co_id`, `po_id`, `weight` (1–3) |
| `COPSOMapping` | `co_pso_mapping` | `co_id`, `pso_id`, `weight` (1–3) |
| `PO` | `po` | `id`, `code` (e.g., "PO1"), `program_id` |
| `PSO` | `pso` | `id`, `code` (e.g., "PSO1"), `specialization_id` |

---

## 3. Constants & Configuration

```java
ATTAINMENT_THRESHOLD = 40.0   // 40% — minimum score to consider a student as "attaining" a CO
PO_SCALE             = 3.0    // PO/PSO attainment is reported on a 0–3 scale

// Exam structure
MID_TERM_MAX_MARKS   = 20.0
END_TERM_MAX_MARKS   = 50.0
TOTAL_MAX_MARKS      = 70.0
```

---

## 4. CO Attainment Calculation

**Method:** `calculateCOAttainment(Long courseId)`

**Output:** `Map<String, Double>` → e.g., `{CO1: 100.0, CO2: 45.5, CO3: 31.8}`  
**Scale:** 0–100 (percentage)

### Algorithm (Step by Step)

#### Step 1: Gather Data
- Fetch all `StudentMark` records for the course
- Fetch all `QuestionCOMapping` records for the course
- If either is empty → return empty map (no data to calculate)

#### Step 2: Compute Maximum Marks per CO
For each `QuestionCOMapping`, sum up `maxMarks` grouped by `co_id`:

```
MaxMarks(CO_i) = Σ QuestionCOMapping.maxMarks   (for all questions mapped to CO_i)
```

> This includes questions from **both** mid-term and end-term exams combined.

#### Step 3: Aggregate Student Marks per CO
For each `StudentMark`:
1. Find the matching `QuestionCOMapping` by comparing `mark.question` with `mapping.questionLabel` (case-insensitive)
2. Sum the student's obtained marks per CO:

```
ObtainedMarks(Student_j, CO_i) = Σ mark.marks   (for all questions mapped to CO_i, across both exam types)
```

#### Step 4: Apply Threshold & Calculate Attainment

For each CO:

```
StudentPercentage(Student_j, CO_i) = (ObtainedMarks(Student_j, CO_i) / MaxMarks(CO_i)) × 100
```

A student **attains** a CO if:

```
StudentPercentage(Student_j, CO_i) ≥ 40%
```

Then:

```
                                    Number of students with percentage ≥ 40%
CO_Attainment(CO_i)  =  ────────────────────────────────────────────────────── × 100
                                         Total number of students
```

### Formula Summary

```
                    Count(students where (obtainedMarks / maxMarks × 100) ≥ 40%)
CO_Attainment(%) = ───────────────────────────────────────────────────────────────── × 100
                                        Total students
```

**Result:** A percentage value (0–100) representing what fraction of students achieved ≥ 40% in that CO.

---

## 5. PO Attainment Calculation

**Method:** `calculatePOAttainment(Long courseId)`

**Output:** `Map<String, Double>` → e.g., `{PO1: 2.1, PO2: 1.5, PO5: 1.8}`  
**Scale:** 0–3.0

### Algorithm (Step by Step)

#### Step 1: Get CO Attainments
Call `calculateCOAttainment(courseId)` to get the CO attainment percentages.  
If empty → return empty map.

#### Step 2: Fetch CO-PO Mappings
Retrieve all `COPOMap` records for the course (via `coPoMappingRepository.findByCoCourseId(courseId)`).

Each mapping contains:
- `co` → the Course Outcome
- `po` → the Program Outcome
- `weight` → 1 (Low), 2 (Medium), or 3 (High) correlation

#### Step 3: Convert CO Attainment to 0–3 Scale

```
CO_Level(CO_i) = (CO_Attainment(CO_i) / 100.0) × 3.0
```

For example, if CO1 attainment = 100.0%, then CO_Level = 3.0.  
If CO2 attainment = 45.5%, then CO_Level = 1.365.

#### Step 4: Calculate Weighted PO Scores

For each CO-PO mapping:

```
Contribution = CO_Level(CO_i) × Weight(CO_i → PO_j)
```

Accumulate for each PO:

```
PO_WeightedSum(PO_j) = Σ [ CO_Level(CO_i) × Weight(CO_i → PO_j) ]    (for all COs mapped to PO_j)
PO_TotalWeight(PO_j) = Σ [ Weight(CO_i → PO_j) ]                      (for all COs mapped to PO_j)
```

#### Step 5: Compute Final PO Attainment

```
                          PO_WeightedSum(PO_j)
PO_Attainment(PO_j)  =  ──────────────────────    (capped at 3.0)
                          PO_TotalWeight(PO_j)
```

### Formula Summary

```
                          Σ (CO_Level_i × Weight_i)
PO_Attainment(0-3)  =   ───────────────────────────   , capped at max 3.0
                              Σ Weight_i

where:
    CO_Level_i = (CO_Attainment_%_i / 100) × 3.0
    Weight_i   = CO-PO mapping weight (1, 2, or 3)
    Sum is over all COs that are mapped to this PO
```

---

## 6. PSO Attainment Calculation

**Method:** `calculatePSOAttainment(Long courseId)`

**Output:** `Map<String, Double>` → e.g., `{PSO1: 1.8, PSO2: 2.1, PSO3: 1.2}`  
**Scale:** 0–3.0

### Algorithm

The PSO calculation follows the **exact same formula** as PO, but uses CO-PSO mappings instead of CO-PO mappings.

#### Step 1: Get CO Attainments
Call `calculateCOAttainment(courseId)` → same as PO.

#### Step 2: Verify Course & Program
Ensure the course exists and has a program assigned.

#### Step 3: Fetch All COs for the Course
Retrieve all `CO` records for the course.

#### Step 4: For Each CO, Fetch CO-PSO Mappings
For each CO, retrieve `COPSOMapping` records (via `coPsoMappingRepository.findByCoId(co.getId())`).

Each mapping contains:
- `co` → the Course Outcome
- `pso` → the Program Specific Outcome
- `weight` → 1, 2, or 3

#### Step 5: Same Weighted Average as PO

```
CO_Level(CO_i) = (CO_Attainment(CO_i) / 100.0) × 3.0
```

```
                           Σ (CO_Level_i × Weight_i)
PSO_Attainment(0-3)  =   ───────────────────────────   , capped at max 3.0
                               Σ Weight_i

where:
    Sum is over all COs that are mapped to this PSO
```

### Key Difference from PO Calculation

| Aspect | PO Calculation | PSO Calculation |
|--------|---------------|-----------------|
| Mapping source | `co_po_mapping` table | `co_pso_mapping` table |
| Query approach | `findByCoCourseId(courseId)` — fetches all CO-PO mappings for the course at once | `findByCoId(coId)` — fetches CO-PSO mappings per CO individually |
| Target entity | `PO` (program-level) | `PSO` (specialization-level) |
| Formula | **Identical** | **Identical** |

---

## 7. CO Attainment by Exam Type (Breakdown)

**Method:** `calculateCOAttainmentByExamType(Long courseId)`

**Output:** `Map<String, Map<String, Double>>` → e.g.:
```json
{
  "mid_term": { "CO1": 85.0, "CO2": 60.0 },
  "end_term": { "CO1": 72.0, "CO2": 45.0 }
}
```

This uses the **same formula as overall CO attainment**, but applied separately to mid-term (`examType = "mid_term"`) and end-term (`examType = "end_term"`) marks.

The max marks per CO per exam type are derived from the actual `StudentMark.maxMarks` values for each exam type, rather than from `QuestionCOMapping.maxMarks`.

---

## 8. Individual Student CO Attainment

**Method:** `calculateIndividualStudentCOAttainment(Long courseId)`

**Output:** `Map<Long, Map<String, Double>>` → student ID → CO code → percentage

For each student, this computes:

```
                                     Σ marks_obtained_for_CO_i
Student_CO_Percentage(S, CO_i)  =   ─────────────────────────── × 100
                                     MaxMarks(CO_i)
```

This is a **raw percentage** (0–100), not a threshold-based calculation. It shows how much each individual student scored in each CO.

---

## 9. Attainment Report (API Output)

**Method:** `getAttainmentReport(Long courseId)`

Returns a comprehensive JSON report combining all calculations:

```json
{
  "course_id": 292,
  "co_attainments_overall": { "CO1": 100.0, "CO2": 45.5, ... },
  "po_attainments": { "PO1": 2.1, "PO2": 1.5, ... },
  "pso_attainments": { "PSO1": 1.8, "PSO2": 2.1, ... },
  "co_attainments_by_exam_type": {
    "mid_term": { "CO1": 85.0, ... },
    "end_term": { "CO1": 72.0, ... }
  },
  "individual_student_co_attainments": {
    "101": { "CO1": 85.0, "CO2": 62.5, ... },
    "102": { "CO1": 92.0, "CO2": 38.0, ... }
  },
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

## 10. Worked Example

### Setup

**Course:** ENCS205 — Data Structures  
**Students:** 22 students  
**COs:** CO1, CO2, CO3, CO4, CO5

Suppose the question-to-CO mapping is:

| Question | CO | Max Marks |
|----------|----|-----------|
| Q1(a) | CO1 | 5 |
| Q1(b) | CO1 | 5 |
| Q2(a) | CO2 | 10 |
| Q3(a) | CO3 | 10 |
| Q4(a) | CO4 | 10 |
| Q5(a) | CO5 | 10 |

So: `MaxMarks(CO1) = 10`, `MaxMarks(CO2) = 10`, etc.

### Step 1: CO Attainment

Suppose:
- **CO1:** All 22 students scored ≥ 40% → `22/22 × 100 = 100.0%`
- **CO2:** 10 students scored ≥ 40% → `10/22 × 100 = 45.45%`
- **CO3:** 7 students scored ≥ 40% → `7/22 × 100 = 31.82%`
- **CO4:** 10 students scored ≥ 40% → `10/22 × 100 = 45.45%`
- **CO5:** 7 students scored ≥ 40% → `7/22 × 100 = 31.82%`

**Result:** `{CO1: 100.0, CO2: 45.45, CO3: 31.82, CO4: 45.45, CO5: 31.82}`

### Step 2: PO Attainment

Suppose the CO-PO mapping matrix is:

| | PO1 | PO2 | PO3 | PO5 |
|---|---|---|---|---|
| CO1 | 3 | 2 | 2 | 2 |
| CO2 | 1 | 2 | – | 3 |
| CO3 | – | – | – | 3 |
| CO4 | – | 2 | – | 3 |

#### Compute CO Levels (0–3 scale):

```
CO1_Level = (100.0 / 100) × 3.0 = 3.0
CO2_Level = (45.45 / 100) × 3.0 = 1.3635
CO3_Level = (31.82 / 100) × 3.0 = 0.9546
CO4_Level = (45.45 / 100) × 3.0 = 1.3635
```

#### PO1 Calculation:

```
Mapped COs: CO1 (weight 3), CO2 (weight 1)

WeightedSum = (3.0 × 3) + (1.3635 × 1) = 9.0 + 1.3635 = 10.3635
TotalWeight = 3 + 1 = 4

PO1 = 10.3635 / 4 = 2.591  (on 0–3 scale)
```

#### PO5 Calculation:

```
Mapped COs: CO1 (weight 2), CO2 (weight 3), CO3 (weight 3), CO4 (weight 3)

WeightedSum = (3.0 × 2) + (1.3635 × 3) + (0.9546 × 3) + (1.3635 × 3)
            = 6.0 + 4.0905 + 2.8638 + 4.0905 = 17.0448
TotalWeight = 2 + 3 + 3 + 3 = 11

PO5 = 17.0448 / 11 = 1.549  (on 0–3 scale)
```

### Step 3: PSO Attainment

Works identically to PO, but uses the CO-PSO mapping matrix.

Suppose:

| | PSO1 | PSO2 |
|---|---|---|
| CO1 | 3 | 2 |
| CO2 | 2 | 3 |

```
PSO1 = (3.0 × 3 + 1.3635 × 2) / (3 + 2) = (9.0 + 2.727) / 5 = 2.345

PSO2 = (3.0 × 2 + 1.3635 × 3) / (2 + 3) = (6.0 + 4.0905) / 5 = 2.018
```

---

## Data Flow Diagram

```
  ┌───────────────────────────────────────────────────────────────────┐
  │                        INPUT DATA                                 │
  │                                                                   │
  │  StudentMark (question, marks, maxMarks, examType, studentId)     │
  │  QuestionCOMapping (questionLabel, coId, maxMarks)                │
  │  CO-PO Mapping (coId, poId, weight)                               │
  │  CO-PSO Mapping (coId, psoId, weight)                             │
  └────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
  ┌───────────────────────────────────────────────────────────────────┐
  │  STEP 1: CO ATTAINMENT  (0-100%)                                  │
  │                                                                   │
  │  1. Sum maxMarks per CO from QuestionCOMapping                    │
  │  2. Sum student marks per CO (all exam types combined)            │
  │  3. For each CO: count students scoring ≥ 40%                    │
  │  4. CO_Attainment = (count / total students) × 100               │
  └────────────────────────────┬──────────────────────────────────────┘
                               │
                    ┌──────────┴──────────┐
                    ▼                     ▼
  ┌──────────────────────────┐  ┌──────────────────────────┐
  │  STEP 2: PO ATTAINMENT   │  │  STEP 2: PSO ATTAINMENT  │
  │  (0-3 scale)              │  │  (0-3 scale)             │
  │                           │  │                          │
  │  CO_Level = CO% / 100 × 3│  │  CO_Level = CO% / 100 × 3│
  │                           │  │                          │
  │  PO = Σ(Level×Weight)     │  │  PSO = Σ(Level×Weight)   │
  │       ─────────────────   │  │        ─────────────────  │
  │          Σ(Weight)        │  │           Σ(Weight)       │
  │                           │  │                          │
  │  Uses: co_po_mapping      │  │  Uses: co_pso_mapping    │
  └──────────────────────────┘  └──────────────────────────┘
```

---

## CO Code Matching Logic

Since CO codes may be stored inconsistently (e.g., `"CO1"` in one table vs `"ENSP201-CO1"` in another), the system uses a suffix-matching fallback:

1. **Try exact match first** — look up `"ENSP201-CO1"` directly in the CO attainment map
2. **If not found**, extract the CO suffix using regex `(CO\d+)$`:
   - `"ENSP201-CO1"` → extracts `"CO1"`
   - `"CO1"` → extracts `"CO1"`
3. **Match by suffix** — compare extracted suffixes to find the correct CO attainment value

This is implemented in the `extractCOSuffix()` helper method.

---

## Summary of Output Scales

| Metric | Scale | Meaning |
|--------|-------|---------|
| CO Attainment | 0–100% | Percentage of students achieving ≥ 40% in that CO |
| PO Attainment | 0–3.0 | Weighted average of CO levels mapped to this PO |
| PSO Attainment | 0–3.0 | Weighted average of CO levels mapped to this PSO |
| Individual Student CO | 0–100% | Raw percentage scored by a specific student in a CO |

---

## Prerequisites for Calculations to Work

For the attainment calculations to produce non-zero results, the following data must exist:

1. ✅ **Student marks** uploaded for the course (both mid-term and end-term)
2. ✅ **Question-CO mappings** defined for the course (linking exam questions to COs)
3. ✅ **CO-PO mappings** defined for the course's COs (for PO calculation)
4. ✅ **CO-PSO mappings** defined for the course's COs (for PSO calculation)

If any of these are missing, the corresponding attainment values will return as empty/zero.

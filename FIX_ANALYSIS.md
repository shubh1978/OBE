# 🔧 PROJECT FIX ANALYSIS & PLAN

**Date:** May 25, 2026  
**Issues Identified:** 4 major issues  
**Status:** Analyzing and Fixing

---

## ISSUES FOUND & FIXES NEEDED

### Issue #1: Data Science in BTech Not Showing Marks
**Problem:** Marks not showing for BTech Data Science specialization
**Root Cause:** Course filtering logic in `getCoursesForFilter()` may not properly handle Data Science specialization courses
**Fix Location:** `OBEDashboardController.java` Lines 689-710
**Expected Result:** Data Science marks should appear like other BTech specializations

### Issue #2: Other Programmes Not Showing Student Performance
**Problem:** BCS, MSC, MTECH, BCA student performance not showing
**Root Cause:** Student performance endpoint may have filtering issues for non-BTech programmes
**Fix Location:** `OBEDashboardController.java` Lines 373-626 (getStudentPerformance)
**Expected Result:** All programmes should show individual student CO attainment

### Issue #3: CO-PO and CO-PSO Mappings in Separate Rows
**Problem:** Frontend shows CO-PO and CO-PSO mappings in different rows
**Need:** Combine into single row per CO
**Current Output:**
```json
{
  "coPoMatrix": [{"co": "CO1", "PO1": 2, "PO2": 1}],
  "coPsoMatrix": [{"co": "CO1", "PSO1": 1, "PSO2": 2}]
}
```
**Desired Output:**
```json
{
  "coMappingMatrix": [{"co": "CO1", "PO1": 2, "PO2": 1, "PSO1": 1, "PSO2": 2}]
}
```
**Fix Location:** `OBEDashboardController.java` Lines 227-272

### Issue #4: Student Performance Shows Too Much Data
**Problem:** Frontend shows PO attainment overall and status for each student
**Need:** Remove PO attainment, overall, and status from student performance
**Current Output per student:**
```json
{
  "enrollmentNo": "2401830001",
  "name": "STUDENT NAME",
  "CO1": 85.5,
  "CO2": 92.3,
  "poAttainment": {"PO1": 78.5, "PO2": 82.3},
  "overall": 87.5,
  "status": "Pass"
}
```
**Desired Output:**
```json
{
  "enrollmentNo": "2401830001",
  "name": "STUDENT NAME",
  "CO1": 85.5,
  "CO2": 92.3
}
```
**Fix Location:** `OBEDashboardController.java` Lines 556-609

---

## IMPLEMENTATION PLAN

### Step 1: Add Combined CO Mapping Matrix (Fix #3)
- Create new combined mapping that includes both PO and PSO
- Keep existing separate matrices for backward compatibility
- Add `"coMappingMatrix"` with all mappings in one row

### Step 2: Simplify Student Performance Response (Fix #4)
- Remove `poAttainment` field
- Remove `overall` field  
- Remove `status` field
- Keep only: enrollmentNo, name, CO1, CO2, CO3, etc.

### Step 3: Debug Data Science BTech Marks (Issue #1)
- Check if Data Science specialization courses are being filtered correctly
- Verify marks exist in database for Data Science
- Test course filtering logic

### Step 4: Debug Multi-Programme Student Performance (Issue #2)
- Test BCS, MSC, MTECH, BCA course loading
- Verify programme filtering in student performance endpoint
- Check specialization assignment for these programmes

---

## TECHNICAL DETAILS

### Issue #1: Data Science Course Filtering
**Current Logic (Lines 694-701):**
```java
if (specializationId!=null)
    courses = courses.stream()
        .filter(c -> c.getSpecialization() == null
                  || specializationId.equals(c.getSpecialization().getId()))
        .collect(Collectors.toList());
```

**Problem:** This should work, but may need to verify:
1. Data Science courses exist in DB with correct specialization_id
2. Specialization ID is correct for Data Science
3. Batch year filtering (lines 702-708) isn't incorrectly filtering out Data Science courses

### Issue #2: Student Performance for Multiple Programmes
**Current Logic (Lines 374-458):**
The endpoint attempts to find the course and then load marks. Issues might be:
1. Course resolution (lines 382-393) may fail for non-BTech courses
2. Sibling course lookup (lines 398-408) might not work for all programmes
3. Specialization filtering (lines 431-454) may be program-specific

### Issue #3: CO Mapping Matrices
**Current Output** (lines 227-272):
- Separate coPoRows and coPsoRows in response
- Response includes: `coPoMatrix`, `poAttainment`, `coPsoMatrix`, `psoAttainment`

**Required Change:**
- Merge into single matrix with CO + PO weights + PSO weights
- Single header row: ["CO", "PO1", "PO2", "PSO1", "PSO2", ...]

### Issue #4: Student Performance Data
**Current Output** (lines 590-608):
```java
row.put("poAttainment", spa);           // Remove this
double overall = ...;
row.put("overall", overall);            // Remove this
row.put("status", overall >= 40 ? "Pass" : "At Risk");  // Remove this
```

**Required Change:**
- Delete these three lines
- Keep only: enrollmentNo, name, CO columns

---

## VALIDATION APPROACH

After fixing, need to verify:
1. ✅ Data Science BTech marks appear in dashboard
2. ✅ BCS/MSC/MTECH/BCA student performance shows data
3. ✅ CO-PO-PSO mappings in single row on frontend
4. ✅ Student performance shows only CO attainment, no PO/overall/status

---

**Next Step:** Implement the fixes in the code


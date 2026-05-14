package org.example.repository;

import org.example.entity.Course;
import org.example.entity.Student;
import org.example.entity.StudentMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StudentMarkRepository extends JpaRepository<StudentMark, Long> {

    List<StudentMark> findByStudent(Student student);

    List<StudentMark> findByCourse(Course course);

    List<StudentMark> findByCourseAndExamType(Course course, String examType);

    List<StudentMark> findByStudentAndCourse(Student student, Course course);

    List<StudentMark> findByStudentAndCourseAndExamType(Student student, Course course, String examType);

    List<StudentMark> findByCourseId(Long courseId);

    // ── JOIN FETCH queries (eliminates N+1 lazy-loading, fixes HTTP 500) ──────

    /** Fetch ALL marks for a course with student + batch eagerly loaded. */
    @Query("SELECT sm FROM StudentMark sm " +
           "JOIN FETCH sm.student st " +
           "LEFT JOIN FETCH st.batch b " +
           "LEFT JOIN FETCH b.specialization " +
           "WHERE sm.course = :course")
    List<StudentMark> findByCourseWithStudent(@Param("course") Course course);

    /**
     * Fetch marks ALREADY FILTERED to a specific specialization at the DB level.
     * Uses INNER JOIN on student.specialization — only returns students where
     * student.specialization_id = specId.  This avoids LAZY-loading issues entirely:
     * no in-memory filtering needed.
     */
    @Query("SELECT sm FROM StudentMark sm " +
           "JOIN FETCH sm.student st " +
           "LEFT JOIN FETCH st.batch b " +
           "LEFT JOIN FETCH b.specialization " +
           "JOIN st.specialization sp " +
           "WHERE sm.course = :course AND sp.id = :specId")
    List<StudentMark> findByCourseAndSpecId(
            @Param("course") Course course,
            @Param("specId") Long specId);

    /**
     * Fallback: fetch marks for a course filtered by enrollment number prefix.
     * Used when students have not had specialization_id set yet (legacy data).
     * The enrollment prefix encodes the branch, e.g. "2401" + "41" = Cyber Security.
     */
    @Query("SELECT sm FROM StudentMark sm " +
           "JOIN FETCH sm.student st " +
           "LEFT JOIN FETCH st.batch b " +
           "WHERE sm.course = :course AND st.enrollmentNumber LIKE :prefix%")
    List<StudentMark> findByCourseAndEnrollmentPrefix(
            @Param("course") Course course,
            @Param("prefix") String prefix);

    /**
     * Fetch marks for a course filtered by enrollment-number spec-codes (digits 5-6).
     * E.g. specCodes=["01"] matches 2301010001 (plain CSE),
     *      specCodes=["17","18"] matches FSD students.
     * Used as fallback when student.specialization_id is null (legacy / pre-assignment data).
     */
    @Query("SELECT sm FROM StudentMark sm " +
           "JOIN FETCH sm.student st " +
           "LEFT JOIN FETCH st.batch b " +
           "LEFT JOIN FETCH b.specialization " +
           "WHERE sm.course = :course AND SUBSTRING(st.enrollmentNumber, 5, 2) IN :specCodes")
    List<StudentMark> findByCourseAndEnrollmentSpecCodes(
            @Param("course") Course course,
            @Param("specCodes") java.util.List<String> specCodes);

    /** Same as above but takes courseId — for AttainmentService (no JOIN FETCH needed). */
    @Query("SELECT sm FROM StudentMark sm " +
           "JOIN sm.student st " +
           "WHERE sm.course.id = :courseId AND SUBSTRING(st.enrollmentNumber, 5, 2) IN :specCodes")
    List<StudentMark> findByCourseIdAndEnrollmentSpecCodes(
            @Param("courseId") Long courseId,
            @Param("specCodes") java.util.List<String> specCodes);

    /** Count distinct students by course and enrollment spec-codes — for course dropdown. */
    @Query("SELECT COUNT(DISTINCT sm.student.id) FROM StudentMark sm " +
           "JOIN sm.student st " +
           "WHERE sm.course = :course AND SUBSTRING(st.enrollmentNumber, 5, 2) IN :specCodes")
    long countDistinctStudentsByCourseAndSpecCodes(
            @Param("course") Course course,
            @Param("specCodes") java.util.List<String> specCodes);


    /** Fetch marks + student + batch for courseId — used by attainment service. */
    @Query("SELECT sm FROM StudentMark sm " +
           "JOIN FETCH sm.student st " +
           "LEFT JOIN FETCH st.batch b " +
           "LEFT JOIN FETCH b.specialization " +
           "WHERE sm.course.id = :courseId")
    List<StudentMark> findByCourseIdWithStudent(@Param("courseId") Long courseId);

    /** Fetch marks for courseId scoped to a specialization — for spec-filtered attainment. */
    @Query("SELECT sm FROM StudentMark sm " +
           "JOIN sm.student st " +
           "JOIN st.specialization sp " +
           "WHERE sm.course.id = :courseId AND sp.id = :specId")
    List<StudentMark> findByCourseIdAndSpecId(
            @Param("courseId") Long courseId,
            @Param("specId") Long specId);


    // ── COUNT queries (avoids loading full rows just to count) ────────────────

    /** Total mark row count for a course — for deduplication ranking. */
    @Query("SELECT COUNT(sm) FROM StudentMark sm WHERE sm.course = :course")
    long countMarksByCourse(@Param("course") Course course);

    /**
     * Bulk count: returns [courseId, count] pairs for ALL courses in one query.
     * Used by deduplicateCourses to avoid N individual countMarksByCourse calls.
     */
    @Query("SELECT sm.course.id, COUNT(sm) FROM StudentMark sm GROUP BY sm.course.id")
    List<Object[]> countMarksByCourseIdBulk();

    /** Distinct student count for a course — for course listing. */
    @Query("SELECT COUNT(DISTINCT sm.student.id) FROM StudentMark sm WHERE sm.course = :course")
    long countDistinctStudentsByCourse(@Param("course") Course course);

    /**
     * Distinct student count scoped to a specific specialization.
     * Joins on student.specialization_id (the direct field set during data ingestion),
     * NOT batch.specialization which was always null on shared batches.
     */
    @Query("SELECT COUNT(DISTINCT sm.student.id) FROM StudentMark sm " +
           "JOIN sm.student st " +
           "JOIN st.specialization sp " +
           "WHERE sm.course = :course AND sp.id = :specializationId")
    long countDistinctStudentsByCourseAndSpecialization(
            @Param("course") Course course,
            @Param("specializationId") Long specializationId);
}

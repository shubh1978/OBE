package org.example.repository;

import org.example.entity.Batch;
import org.example.entity.Program;
import org.example.entity.Specialization;
import org.example.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findFirstByEnrollmentNumber(String enrollmentNumber);
    default Optional<Student> findByEnrollmentNumber(String enrollmentNumber) { return findFirstByEnrollmentNumber(enrollmentNumber); }
    List<Student> findByBatch(Batch batch);
    List<Student> findByProgram(Program program);
    List<Student> findBySpecialization(Specialization specialization);

    /** Find students with no specialization assigned — avoids loading all students into memory. */
    List<Student> findBySpecializationIsNull();

    /** Find null-spec students for a specific programme — used in single-spec fallback. */
    List<Student> findByProgramAndSpecializationIsNull(Program program);

    /** Bulk-assign specialization to all students whose enrollment numbers start with a given prefix. */
    @Modifying
    @Query("UPDATE Student s SET s.specialization = :spec WHERE s.enrollmentNumber LIKE :prefix%")
    int assignSpecializationByEnrollmentPrefix(@Param("spec") Specialization spec, @Param("prefix") String prefix);

    /** Bulk-assign specialization to students whose enrollment numbers fall in a range (string comparison). */
    @Modifying
    @Query("UPDATE Student s SET s.specialization = :spec WHERE s.enrollmentNumber >= :from AND s.enrollmentNumber <= :to")
    int assignSpecializationByEnrollmentRange(@Param("spec") Specialization spec, @Param("from") String from, @Param("to") String to);

    /** Count students with no specialization assigned. */
    @Query("SELECT COUNT(s) FROM Student s WHERE s.specialization IS NULL")
    long countUnassignedStudents();

    /**
     * Returns distinct 2-digit enrollment year prefixes for students in a programme.
     * Used to populate the batch year dropdown so "2024 Batch" shows even when no
     * 2024 batch entity exists in the DB.
     */
    @Query("SELECT DISTINCT SUBSTRING(s.enrollmentNumber, 1, 2) " +
           "FROM Student s " +
           "WHERE s.program = :program " +
           "AND s.enrollmentNumber IS NOT NULL " +
           "AND LENGTH(s.enrollmentNumber) >= 2 " +
           "AND SUBSTRING(s.enrollmentNumber, 1, 2) BETWEEN '20' AND '29' " +
           "ORDER BY SUBSTRING(s.enrollmentNumber, 1, 2) DESC")
    List<String> findDistinctEnrollmentYearPrefixesByProgram(
            @Param("program") org.example.entity.Program program);
}
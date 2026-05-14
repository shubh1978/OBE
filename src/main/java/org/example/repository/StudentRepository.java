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
}
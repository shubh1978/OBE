package org.example.repository;

import org.example.entity.Batch;
import org.example.entity.Program;
import org.example.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findFirstByEnrollmentNumber(String enrollmentNumber);
    default Optional<Student> findByEnrollmentNumber(String enrollmentNumber) { return findFirstByEnrollmentNumber(enrollmentNumber); }
    List<Student> findByBatch(Batch batch);
    List<Student> findByProgram(Program program);
}
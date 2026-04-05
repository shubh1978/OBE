package org.example.repository;

import org.example.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;


public interface CourseRepository extends JpaRepository<Course, Long> {

    Optional<Course> findByCourseCode(String courseCode);

    List<Course> findBySemester(Semester semester);

    List<Course> findByProgram(Program program);
    List<Course> findBySpecialization(Specialization specialization);

    Optional<Course> findFirstByCourseCodeAndBatch(String courseCode, Batch batch);
    default Optional<Course> findByCourseCodeAndBatch(String courseCode, Batch batch) { return findFirstByCourseCodeAndBatch(courseCode, batch); }

    // Use @Query to find by semester ID directly — avoids JPA object equality issues
    @org.springframework.data.jpa.repository.Query("SELECT c FROM Course c WHERE c.semester.id = :semesterId")
    List<Course> findBySemesterId(@org.springframework.data.repository.query.Param("semesterId") Long semesterId);
    Optional<Course> findFirstByCourseCode(String courseCode);

    List<Course> findByBatch(org.example.entity.Batch b);
}
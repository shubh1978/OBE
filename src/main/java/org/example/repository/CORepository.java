package org.example.repository;

import org.example.entity.CO;
import org.example.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CORepository extends JpaRepository<CO, Long> {

    List<CO> findByCourseId(Long courseId);

    Optional<CO> findFirstByCodeAndCourse(String code, Course course);
    default Optional<CO> findByCodeAndCourse(String code, Course course) { return findFirstByCodeAndCourse(code, course); }

    List<CO> findByCourse(Course course);
}

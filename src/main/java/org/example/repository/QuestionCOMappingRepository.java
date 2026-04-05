package org.example.repository;

import org.example.entity.Course;
import org.example.entity.QuestionCOMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuestionCOMappingRepository extends JpaRepository<QuestionCOMapping, Long> {

    List<QuestionCOMapping> findByCourseId(Long courseId);

    List<QuestionCOMapping> findByCourse(Course course);

    Optional<QuestionCOMapping> findByCourseAndQuestionLabel(Course course, String questionLabel);
}


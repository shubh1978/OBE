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

    @org.springframework.data.jpa.repository.Query(
        "SELECT qcm FROM QuestionCOMapping qcm " +
        "JOIN FETCH qcm.co co " +
        "WHERE qcm.course.courseCode = :courseCode " +
        "ORDER BY qcm.course.id ASC")
    List<QuestionCOMapping> findByCourseCode(@org.springframework.data.repository.query.Param("courseCode") String courseCode);
}



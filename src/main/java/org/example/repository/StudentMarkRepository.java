package org.example.repository;

import org.example.entity.Course;
import org.example.entity.Student;
import org.example.entity.StudentMark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudentMarkRepository extends JpaRepository<StudentMark, Long> {

    List<StudentMark> findByStudent(Student student);

    List<StudentMark> findByCourse(Course course);

    List<StudentMark> findByCourseAndExamType(Course course, String examType);

    List<StudentMark> findByStudentAndCourse(Student student, Course course);

    List<StudentMark> findByStudentAndCourseAndExamType(Student student, Course course, String examType);

    List<StudentMark> findByCourseId(Long courseId);
}

package org.example.service;

import org.example.entity.CO;
import org.example.entity.Course;
import org.example.repository.CORepository;
import org.example.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


import java.util.List;


@Service
@RequiredArgsConstructor
public class COService {
private final CORepository coRepository;
private final CourseRepository courseRepository;


public CO create(Long courseId, CO co) {
Course course = courseRepository.findById(courseId).orElseThrow();
co.setCourse(course);
return coRepository.save(co);
}


public List<CO> getByCourse(Long courseId) {
return coRepository.findByCourseId(courseId);
}
}
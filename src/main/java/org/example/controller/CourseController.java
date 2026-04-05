package org.example.controller;

import org.example.entity.Course;
import org.example.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


import java.util.List;


@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {


private final CourseService courseService;


@PostMapping
public Course createCourse(@RequestParam Long batchId,
@RequestParam Long programId,
@RequestParam Long specializationId,
@RequestParam Long semesterId,
@RequestBody Course course) {
return courseService.create(batchId, programId, specializationId, semesterId, course);
}


@GetMapping
public List<Course> getAllCourses() {
return courseService.getAll();
}
}
package org.example.service;

import org.example.entity.*;
import org.example.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


import java.util.List;
import java.util.Optional;


@Service
@RequiredArgsConstructor
public class CourseService {


private final CourseRepository courseRepository;
private final BatchRepository batchRepository;
private final ProgramRepository programRepository;
private final SpecializationRepository specializationRepository;
private final SemesterRepository semesterRepository;


public Course create(Long batchId, Long programId, Long specializationId, Long semesterId, Course course) {
course.setBatch(batchRepository.findById(batchId).orElseThrow());
course.setProgram(programRepository.findById(programId).orElseThrow());
course.setSpecialization(specializationRepository.findById(specializationId).orElseThrow());
course.setSemester(semesterRepository.findById(semesterId).orElseThrow());
return courseRepository.save(course);
}

/**
 * Find existing course by code or create new one
 * IMPORTANT: Always searches by course code first to prevent duplicates
 */
public Course findOrCreateCourse(String courseCode, String courseName, 
                                  Long batchId, Long programId, 
                                  Long specializationId, Long semesterId) {
    // ALWAYS search by course code first
    Optional<Course> existingCourse = courseRepository.findByCourseCode(courseCode);
    
    if (existingCourse.isPresent()) {
        return existingCourse.get();
    }
    
    // Only create if doesn't exist
    Course newCourse = new Course();
    newCourse.setCourseCode(courseCode);
    newCourse.setCourseName(courseName);
    
    if (batchId != null) {
        newCourse.setBatch(batchRepository.findById(batchId).orElse(null));
    }
    if (programId != null) {
        newCourse.setProgram(programRepository.findById(programId).orElse(null));
    }
    if (specializationId != null) {
        newCourse.setSpecialization(specializationRepository.findById(specializationId).orElse(null));
    }
    if (semesterId != null) {
        newCourse.setSemester(semesterRepository.findById(semesterId).orElse(null));
    }
    
    return courseRepository.save(newCourse);
}


public List<Course> getAll() {
return courseRepository.findAll();
}
}
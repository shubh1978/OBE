package org.example;

import org.example.entity.Batch;
import org.example.entity.Course;
import org.example.repository.BatchRepository;
import org.example.repository.CourseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
public class DebugDBTest {

    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private BatchRepository batchRepository;

    @Test
    public void dumpData() {
        System.out.println("================== DEBUG DUMP START ==================");
        List<Course> courses = courseRepository.findBySemesterId(79L);
        System.out.println("Found " + courses.size() + " courses for semester 79.");
        for (Course c : courses) {
            System.out.println("Course: " + c.getCourseCode() + " -> " + c.getCourseName());
            Batch b = c.getBatch();
            if (b != null) {
                System.out.println("  Batch ID: " + b.getId() + ", StartYear: " + b.getStartYear() + ", EndYear: " + b.getEndYear());
            } else {
                System.out.println("  Batch: null");
            }
        }
        System.out.println("================== DEBUG DUMP END ==================");
    }
}

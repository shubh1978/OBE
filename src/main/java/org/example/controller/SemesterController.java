package org.example.controller;

import org.example.entity.Semester;
import org.example.service.SemesterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


import java.util.List;


@RestController
@RequestMapping("/api/semesters")
@RequiredArgsConstructor
public class SemesterController {


private final SemesterService semesterService;


@PostMapping
public Semester create(@RequestBody Semester semester) {
return semesterService.create(semester);
}


@GetMapping
public List<Semester> getAll() {
return semesterService.getAll();
}
}
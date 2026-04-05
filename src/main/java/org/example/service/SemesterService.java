package org.example.service;

import org.example.entity.Semester;
import org.example.repository.SemesterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


import java.util.List;


@Service
@RequiredArgsConstructor
public class SemesterService {
private final SemesterRepository semesterRepository;


public Semester create(Semester semester) {
return semesterRepository.save(semester);
}


public List<Semester> getAll() {
return semesterRepository.findAll();
}
}
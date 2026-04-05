package org.example.service;

import lombok.RequiredArgsConstructor;
import org.example.entity.Program;
import org.example.repository.ProgramRepository;
import org.springframework.stereotype.Service;


import java.util.List;


@Service
@RequiredArgsConstructor
public class ProgramService {
private final ProgramRepository programRepository;


public Program create(Program program) {
return programRepository.save(program);
}


public List<Program> getAll() {
return programRepository.findAll();
}
}
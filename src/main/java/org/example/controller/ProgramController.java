package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.entity.Program;
import org.example.service.ProgramService;
import org.springframework.web.bind.annotation.*;


import java.util.List;


@RestController
@RequestMapping("/api/programs")
@RequiredArgsConstructor
public class ProgramController {


private final ProgramService programService;


@PostMapping
public Program createProgram(@RequestBody Program program) {
return programService.create(program);
}


@GetMapping
public List<Program> getAllPrograms() {
return programService.getAll();
}
}
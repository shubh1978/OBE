package org.example.controller;

import org.example.entity.Specialization;
import org.example.service.SpecializationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


import java.util.List;


@RestController
@RequestMapping("/api/specializations")
@RequiredArgsConstructor
public class SpecializationController {


private final SpecializationService specializationService;


@PostMapping
public Specialization create(@RequestBody Specialization specialization) {
return specializationService.create(specialization);
}


@GetMapping
public List<Specialization> getAll() {
return specializationService.getAll();
}
}
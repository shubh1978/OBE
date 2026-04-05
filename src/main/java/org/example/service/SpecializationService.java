package org.example.service;

import org.example.entity.Specialization;
import org.example.repository.SpecializationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


import java.util.List;


@Service
@RequiredArgsConstructor
public class SpecializationService {
private final SpecializationRepository specializationRepository;


public Specialization create(Specialization specialization) {
return specializationRepository.save(specialization);
}


public List<Specialization> getAll() {
return specializationRepository.findAll();
}
}
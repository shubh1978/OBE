package org.example.repository;

import org.example.entity.Program;
import org.example.entity.Specialization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;


public interface SpecializationRepository extends JpaRepository<Specialization, Long> {
    Optional<Specialization> findByName(String specializationName);
    Optional<Specialization> findFirstByNameAndProgram(String name, Program program);
    default Optional<Specialization> findByNameAndProgram(String name, Program program) { return findFirstByNameAndProgram(name, program); }

    List<Specialization> findByProgram(Program program);
}
package org.example.repository;

import org.example.entity.PSO;
import org.example.entity.Program;
import org.example.entity.Specialization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PSORepository extends JpaRepository<PSO, Long> {
    Optional<PSO> findFirstByCode(String code);
    default Optional<PSO> findByCode(String code) { return findFirstByCode(code); }

    Optional<PSO> findByCodeAndProgram(String s, Program program);

    List<PSO> findByProgram(Program program);

    List<PSO> findBySpecialization(Specialization spec);
}

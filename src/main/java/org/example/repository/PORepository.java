package org.example.repository;

import org.example.entity.PO;
import org.example.entity.Program;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PORepository extends JpaRepository<PO, Long> {

    List<PO> findByProgramId(Long programId);

    Optional<PO> findFirstByCode(String code);
    default Optional<PO> findByCode(String code) { return findFirstByCode(code); }

    Optional<PO> findByCodeAndProgram(String code, Program program);

    List<PO> findByProgram(Program program);
}
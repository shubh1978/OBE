package org.example.repository;

import org.example.entity.Program;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProgramRepository extends JpaRepository<Program,Long> {

    Optional<Program> findFirstByName(String name);
    default Optional<Program> findByName(String name) { return findFirstByName(name); }

}

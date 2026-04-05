package org.example.repository;

import org.example.entity.CO;
import org.example.entity.COPSOMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface COPSORepository extends JpaRepository<COPSOMapping, Long> {
    List<COPSOMapping> findByCo(CO co);

    List<COPSOMapping> findByCoId(@org.springframework.data.repository.query.Param("coId") Long coId);
}

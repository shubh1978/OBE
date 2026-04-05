package org.example.repository;

import org.example.entity.CO;
import org.example.entity.COPOMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CO_PO_MappingRepository extends JpaRepository<COPOMap, Long> {

    // Find all CO-PO mappings for a specific course
    @Query("SELECT m FROM COPOMap m WHERE m.co.course.id = :courseId")
    List<COPOMap> findByCoCourseId(@Param("courseId") Long courseId);

    List<COPOMap> findByCo(CO co);

    List<COPOMap> findByCoId(@Param("coId") Long coId);}

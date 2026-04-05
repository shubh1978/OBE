package org.example.repository;

import org.example.entity.Batch;
import org.example.entity.Semester;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;


public interface SemesterRepository extends JpaRepository<Semester, Long> {

    Optional<Semester> findByNumberAndBatchId(int number, Long batchId);

    List<Semester> findByBatch(Batch batch);

    List<Semester> findByNumber(int number);

    default Optional<Semester> findByNumberAndBatch(Integer number, Batch batch) {
        return findFirstByNumberAndBatch(number, batch);}

    Optional<Semester> findFirstByNumberAndBatch(int fsn, Batch fb);
}
package org.example.repository;

import org.example.entity.Batch;
import org.example.entity.Program;
import org.example.entity.Specialization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BatchRepository extends JpaRepository<Batch,Long> {

    Optional<Batch> findByStartYearAndEndYear(int startYear, int endYear);

    List<Batch> findBySpecialization(Specialization spec);

    Optional<Batch> findFirstByStartYearAndProgramAndSpecialization(
            Integer startYear, Program program, Specialization specialization);
    default Optional<Batch> findByStartYearAndProgramAndSpecialization(
            Integer startYear, Program program, Specialization specialization) {
        return findFirstByStartYearAndProgramAndSpecialization(startYear, program, specialization);
    }
    List<Batch> findByProgram(Program prog);

    /** Filter batches by program ID — used by the dashboard batch dropdown. */
    @Query("SELECT b FROM Batch b WHERE b.program.id = :programId")
    List<Batch> findByProgramId(@Param("programId") Long programId);

    /** Used by QuestionWiseReportIngestionService to find the right batch for a class. */
    List<Batch> findByProgramAndStartYear(Program program, int startYear);
}

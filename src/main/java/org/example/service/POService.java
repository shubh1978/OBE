package org.example.service;

import org.example.entity.PO;
import org.example.entity.Program;
import org.example.repository.PORepository;
import org.example.repository.ProgramRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


import java.util.List;


@Service
@RequiredArgsConstructor
public class POService {
private final PORepository poRepository;
private final ProgramRepository programRepository;


public PO create(Long programId, PO po) {
Program program = programRepository.findById(programId).orElseThrow();
po.setProgram(program);
return poRepository.save(po);
}


public List<PO> getByProgram(Long programId) {
return poRepository.findByProgramId(programId);
}
}
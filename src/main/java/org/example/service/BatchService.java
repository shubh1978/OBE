package org.example.service;

import lombok.RequiredArgsConstructor;
import org.example.entity.Batch;
import org.example.repository.BatchRepository;
import org.springframework.stereotype.Service;


import java.util.List;


@Service
@RequiredArgsConstructor
public class BatchService {
private final BatchRepository batchRepository;


public Batch create(Batch batch) {
return batchRepository.save(batch);
}


public List<Batch> getAll() {
return batchRepository.findAll();
}
}
package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.entity.Batch;
import org.example.service.BatchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/api/batches")
@RequiredArgsConstructor
public class BatchController {


private final BatchService batchService;


@PostMapping
public Batch createBatch(@RequestBody Batch batch) {
return batchService.create(batch);
}


@GetMapping
public List<Batch> getAllBatches() {
return batchService.getAll();
}
}
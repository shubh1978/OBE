package org.example.controller;

import org.example.entity.PO;
import org.example.service.POService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


import java.util.List;


@RestController
@RequestMapping("/api/pos")
@RequiredArgsConstructor
public class POController {


private final POService poService;


@PostMapping
public PO createPO(@RequestParam Long programId, @RequestBody PO po) {
return poService.create(programId, po);
}


@GetMapping("/by-program/{programId}")
public List<PO> getPOsByProgram(@PathVariable Long programId) {
return poService.getByProgram(programId);
}
}
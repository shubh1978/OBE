package org.example.controller;

import org.example.entity.COPOMap;
import org.example.service.CO_PO_MappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/co-po-mapping")
@RequiredArgsConstructor
public class CO_PO_MappingController {


private final CO_PO_MappingService mappingService;


@PostMapping
public COPOMap mapCOtoPO(@RequestParam Long coId,
                         @RequestParam Long poId,
                         @RequestParam int level) {
return mappingService.map(coId, poId, level);
}
}
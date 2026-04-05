package org.example.service;

import lombok.Data;
import org.example.entity.*;
import org.example.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
@Data
public class CO_PO_MappingService {
private final CORepository coRepository;
private final PORepository poRepository;
private final CO_PO_MappingRepository mappingRepository;


public COPOMap map(Long coId, Long poId, int level) {
CO co = coRepository.findById(coId).orElseThrow();
PO po = poRepository.findById(poId).orElseThrow();


COPOMap mapping = new COPOMap();
mapping.setCo(co);
mapping.setPo(po);
mapping.setWeight(level);


return mappingRepository.save(mapping);
}
}
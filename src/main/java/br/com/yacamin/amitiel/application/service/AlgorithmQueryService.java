package br.com.yacamin.amitiel.application.service;

import br.com.yacamin.amitiel.adapter.out.persistence.AlgorithmConfigRepository;
import br.com.yacamin.amitiel.domain.AlgorithmConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class AlgorithmQueryService {

    private final AlgorithmConfigRepository algorithmConfigRepository;

    public List<Map<String, Object>> listAll() {
        List<AlgorithmConfig> configs = algorithmConfigRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AlgorithmConfig c : configs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("name", c.getName());
            item.put("engine", c.getEngine());
            item.put("active", c.isActive());
            result.add(item);
        }
        return result;
    }

    public List<String> listActiveNames() {
        return algorithmConfigRepository.findByActiveTrue().stream()
                .map(AlgorithmConfig::getName)
                .toList();
    }

    public List<String> listAllNames() {
        return algorithmConfigRepository.findAll().stream()
                .map(AlgorithmConfig::getName)
                .toList();
    }
}

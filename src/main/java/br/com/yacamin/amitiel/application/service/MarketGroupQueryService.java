package br.com.yacamin.amitiel.application.service;

import br.com.yacamin.amitiel.adapter.out.persistence.MarketGroupRepository;
import br.com.yacamin.amitiel.domain.MarketGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class MarketGroupQueryService {

    private final MarketGroupRepository marketGroupRepository;

    public List<Map<String, Object>> listGroups() {
        List<MarketGroup> groups = marketGroupRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (MarketGroup g : groups) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", g.getId());
            item.put("slugPrefix", g.getSlugPrefix());
            item.put("displayName", g.getDisplayName());
            item.put("blockDuration", g.getBlockDuration());
            item.put("active", g.isActive());
            result.add(item);
        }
        return result;
    }
}

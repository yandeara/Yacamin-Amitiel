package br.com.yacamin.amitiel.adapter.out.persistence;

import br.com.yacamin.amitiel.domain.MarketGroup;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MarketGroupRepository extends MongoRepository<MarketGroup, String> {

    List<MarketGroup> findByActiveTrue();
}

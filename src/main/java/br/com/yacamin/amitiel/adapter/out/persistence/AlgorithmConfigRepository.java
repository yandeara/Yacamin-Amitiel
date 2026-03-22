package br.com.yacamin.amitiel.adapter.out.persistence;

import br.com.yacamin.amitiel.domain.AlgorithmConfig;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AlgorithmConfigRepository extends MongoRepository<AlgorithmConfig, String> {

    List<AlgorithmConfig> findByActiveTrue();
}

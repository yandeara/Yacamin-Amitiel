package br.com.yacamin.amitiel.adapter.out.persistence;

import br.com.yacamin.amitiel.domain.SimEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SimEventRepository extends MongoRepository<SimEvent, String> {

    List<SimEvent> findBySlugAndAlgorithmOrderByTimestampAsc(String slug, String algorithm);

    List<SimEvent> findByTimestampBetweenOrderByTimestampDesc(long from, long to);

    List<SimEvent> findByAlgorithmAndTimestampBetweenOrderByTimestampDesc(String algorithm, long from, long to);

    List<SimEvent> findByTypeAndTimestampGreaterThanEqual(String type, long timestamp);

    List<SimEvent> findByTypeAndAlgorithmAndTimestampGreaterThanEqual(String type, String algorithm, long timestamp);

    List<SimEvent> findByTypeAndTimestampBetweenOrderByTimestampDesc(String type, long from, long to);
}

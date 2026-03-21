package br.com.yacamin.amitiel.adapter.out.persistence;

import br.com.yacamin.amitiel.domain.SimPnlEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SimPnlEventRepository extends MongoRepository<SimPnlEvent, String> {

    List<SimPnlEvent> findByTimestampGreaterThanEqual(long timestamp);

    List<SimPnlEvent> findByTimestampBetween(long from, long to);

    List<SimPnlEvent> findByAlgorithmAndTimestampGreaterThanEqual(String algorithm, long timestamp);

    List<SimPnlEvent> findByAlgorithmAndTimestampBetween(String algorithm, long from, long to);

    List<SimPnlEvent> findByAlgorithmAndTimestampGreaterThanEqualOrderByTimestampDesc(String algorithm, long timestamp);

    List<SimPnlEvent> findByTimestampGreaterThanEqualOrderByTimestampDesc(long timestamp);

    List<SimPnlEvent> findByTimestampBetweenOrderByTimestampDesc(long from, long to);

    List<SimPnlEvent> findByAlgorithmAndTimestampBetweenOrderByTimestampDesc(String algorithm, long from, long to);
}

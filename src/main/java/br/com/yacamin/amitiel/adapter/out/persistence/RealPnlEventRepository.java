package br.com.yacamin.amitiel.adapter.out.persistence;

import br.com.yacamin.amitiel.domain.RealPnlEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface RealPnlEventRepository extends MongoRepository<RealPnlEvent, String> {

    List<RealPnlEvent> findByTimestampGreaterThanEqual(long timestamp);

    List<RealPnlEvent> findByTimestampBetween(long from, long to);

    List<RealPnlEvent> findByTimestampGreaterThanEqualOrderByTimestampDesc(long timestamp);

    List<RealPnlEvent> findByTimestampBetweenOrderByTimestampDesc(long from, long to);

    List<RealPnlEvent> findByMarketUnixTime(long marketUnixTime);
}

package br.com.yacamin.amitiel.adapter.out.persistence;

import br.com.yacamin.amitiel.domain.Event;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface EventRepository extends MongoRepository<Event, String> {

    List<Event> findBySlugOrderByTimestampAsc(String slug);

    List<Event> findByTimestampBetweenOrderByTimestampDesc(long from, long to);

    List<Event> findByTypeAndTimestampGreaterThanEqual(String type, long timestamp);
}

package br.com.yacamin.amitiel.adapter.out.persistence;

import br.com.yacamin.amitiel.domain.AmitielConfigDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AmitielConfigRepository extends MongoRepository<AmitielConfigDoc, String> {
}

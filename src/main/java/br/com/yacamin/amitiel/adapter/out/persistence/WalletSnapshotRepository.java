package br.com.yacamin.amitiel.adapter.out.persistence;

import br.com.yacamin.amitiel.domain.WalletSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface WalletSnapshotRepository extends MongoRepository<WalletSnapshot, String> {

    List<WalletSnapshot> findAllByOrderByTimestampDesc();

    Optional<WalletSnapshot> findFirstByBaselineTrueOrderByTimestampDesc();

    Optional<WalletSnapshot> findFirstByOrderByTimestampDesc();
}

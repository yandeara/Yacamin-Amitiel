package br.com.yacamin.amitiel.application.service.config;

import br.com.yacamin.amitiel.adapter.out.persistence.AmitielConfigRepository;
import br.com.yacamin.amitiel.domain.AmitielConfigDoc;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class AmitielConfigPersistenceService {

    private static final String CONFIG_ID = "amitiel";
    private static final String MODULE_NAME = "amitiel";

    private final AmitielConfigRepository repository;
    private final AmitielConfigService configService;

    @Value("${app.auto-snapshot.enabled:true}")
    private boolean profileAutoSnapshotEnabled;

    @PostConstruct
    public void loadOnStartup() {
        boolean profileValue = profileAutoSnapshotEnabled;

        try {
            Optional<AmitielConfigDoc> opt = repository.findById(CONFIG_ID);
            if (opt.isPresent()) {
                applyFromDoc(opt.get());
                log.info("[CONFIG] Configuracoes carregadas do MongoDB: {}", configService.getConfigMap());
            } else {
                log.info("[CONFIG] Nenhuma configuracao salva, usando defaults: {}", configService.getConfigMap());
            }
        } catch (Exception e) {
            log.error("[CONFIG] Erro ao carregar configuracoes do MongoDB, usando defaults: {}", e.getMessage());
        }

        if (!profileValue) {
            configService.setAutoSnapshotEnabled(false);
            log.info("[CONFIG] Auto-snapshot desligado pelo profile (app.auto-snapshot.enabled=false)");
        }
    }

    public void save() {
        AmitielConfigDoc doc = AmitielConfigDoc.builder()
                .id(CONFIG_ID)
                .module(MODULE_NAME)
                .autoSnapshotEnabled(configService.isAutoSnapshotEnabled())
                .autoSnapshotIntervalMinutes(configService.getAutoSnapshotIntervalMinutes())
                .build();
        repository.save(doc);
        log.info("[CONFIG] Configuracoes salvas no MongoDB: {}", configService.getConfigMap());
    }

    private void applyFromDoc(AmitielConfigDoc doc) {
        configService.setAutoSnapshotEnabled(doc.isAutoSnapshotEnabled());
        if (doc.getAutoSnapshotIntervalMinutes() > 0) {
            configService.setAutoSnapshotIntervalMinutes(doc.getAutoSnapshotIntervalMinutes());
        }
    }
}

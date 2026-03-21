package br.com.yacamin.amitiel.application.service.wallet;

import br.com.yacamin.amitiel.adapter.out.persistence.WalletSnapshotRepository;
import br.com.yacamin.amitiel.application.service.config.AmitielConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class AutoSnapshotScheduler {

    private static final ZoneId SP_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final AmitielConfigService configService;
    private final WalletBalanceService walletBalanceService;
    private final WalletSnapshotRepository snapshotRepository;

    private volatile long lastAutoSnapshotMs = 0;

    @PostConstruct
    public void init() {
        snapshotRepository.findFirstByOrderByTimestampDesc()
                .ifPresent(s -> {
                    lastAutoSnapshotMs = s.getTimestamp();
                    log.info("[AUTO-SNAP] Recuperado ultimo snapshot: {}",
                            Instant.ofEpochMilli(s.getTimestamp()).atZone(SP_ZONE).format(DT_FMT));
                });
    }

    /**
     * Roda a cada 1 minuto. Verifica se o minuto atual esta alinhado ao intervalo configurado.
     * Ex: intervalo=5 → dispara em :00, :05, :10, :15...
     * Ex: intervalo=10 → dispara em :00, :10, :20, :30...
     */
    @Scheduled(fixedRate = 60_000)
    public void checkAndSnapshot() {
        if (!configService.isAutoSnapshotEnabled()) {
            return;
        }

        int interval = configService.getAutoSnapshotIntervalMinutes();
        if (interval <= 0) return;

        ZonedDateTime now = ZonedDateTime.now(SP_ZONE);
        int totalMinutes = now.getHour() * 60 + now.getMinute();

        if (totalMinutes % interval != 0) {
            return; // Nao esta na janela
        }

        // Protecao contra disparo duplicado (mesmo minuto)
        long windowStart = now.withSecond(0).withNano(0).toInstant().toEpochMilli();
        if (Math.abs(lastAutoSnapshotMs - windowStart) < 60_000) {
            return; // Ja disparou neste minuto
        }

        log.info("[AUTO-SNAP] Janela atingida (intervalo={}min, hora={}), criando snapshot",
                interval, now.format(DateTimeFormatter.ofPattern("HH:mm")));
        try {
            walletBalanceService.takeSnapshot();
            lastAutoSnapshotMs = System.currentTimeMillis();
            log.info("[AUTO-SNAP] Snapshot automatico criado com sucesso");
        } catch (Exception e) {
            log.error("[AUTO-SNAP] Erro ao criar snapshot automatico: {}", e.getMessage(), e);
        }
    }

    public Map<String, Object> getAutoStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", configService.isAutoSnapshotEnabled());
        status.put("intervalMinutes", configService.getAutoSnapshotIntervalMinutes());

        if (lastAutoSnapshotMs > 0) {
            status.put("lastAutoSnapshot", lastAutoSnapshotMs);
            status.put("lastAutoSnapshotFormatted",
                    Instant.ofEpochMilli(lastAutoSnapshotMs).atZone(SP_ZONE).format(DT_FMT));
        }

        // Calcular proxima janela
        if (configService.isAutoSnapshotEnabled()) {
            int interval = configService.getAutoSnapshotIntervalMinutes();
            if (interval > 0) {
                ZonedDateTime now = ZonedDateTime.now(SP_ZONE);
                int totalMinutes = now.getHour() * 60 + now.getMinute();
                int nextMinute = ((totalMinutes / interval) + 1) * interval;
                int nextHour = (nextMinute / 60) % 24;
                int nextMin = nextMinute % 60;
                String suffix = nextMinute >= 1440 ? " (amanha)" : "";
                status.put("nextWindow", String.format("%02d:%02d%s", nextHour, nextMin, suffix));
            }
        }

        return status;
    }
}

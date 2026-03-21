package br.com.yacamin.amitiel.application.service.wallet;

import br.com.yacamin.amitiel.adapter.out.persistence.WalletSnapshotRepository;
import br.com.yacamin.amitiel.application.service.config.AmitielConfigService;
import br.com.yacamin.amitiel.domain.WalletSnapshot;
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
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final long WINDOW_TOLERANCE_MINUTES = 2;

    private final AmitielConfigService configService;
    private final WalletBalanceService walletBalanceService;
    private final WalletSnapshotRepository snapshotRepository;

    private volatile long lastAutoSnapshotMs = 0;

    @PostConstruct
    public void init() {
        // Recover last auto snapshot from DB to prevent double-fire on restart
        snapshotRepository.findFirstByOrderByTimestampDesc()
                .ifPresent(s -> {
                    lastAutoSnapshotMs = s.getTimestamp();
                    log.info("[AUTO-SNAP] Recuperado ultimo snapshot: {}",
                            Instant.ofEpochMilli(s.getTimestamp()).atZone(SP_ZONE).format(DT_FMT));
                });
    }

    @Scheduled(fixedRate = 300_000) // 5 minutes
    public void checkAndSnapshot() {
        if (!configService.isAutoSnapshotEnabled()) {
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(SP_ZONE);
        LocalTime currentTime = now.toLocalTime();

        for (String windowStr : configService.getAutoSnapshotWindows()) {
            try {
                LocalTime windowTime = LocalTime.parse(windowStr, TIME_FMT);
                long diffMinutes = Math.abs(Duration.between(currentTime, windowTime).toMinutes());

                // Handle midnight wrap (e.g., 23:59 vs 00:00)
                if (diffMinutes > 720) diffMinutes = 1440 - diffMinutes;

                if (diffMinutes <= WINDOW_TOLERANCE_MINUTES) {
                    // Check we haven't already fired this window
                    long windowEpochMs = now.toLocalDate()
                            .atTime(windowTime)
                            .atZone(SP_ZONE)
                            .toInstant()
                            .toEpochMilli();

                    if (Math.abs(lastAutoSnapshotMs - windowEpochMs) < 10 * 60 * 1000) {
                        return; // Already fired for this window
                    }

                    log.info("[AUTO-SNAP] Janela {} atingida, criando snapshot automatico", windowStr);
                    try {
                        walletBalanceService.takeSnapshot();
                        lastAutoSnapshotMs = System.currentTimeMillis();
                        log.info("[AUTO-SNAP] Snapshot automatico criado com sucesso");
                    } catch (Exception e) {
                        log.error("[AUTO-SNAP] Erro ao criar snapshot automatico: {}", e.getMessage(), e);
                    }
                    return;
                }
            } catch (Exception e) {
                log.warn("[AUTO-SNAP] Window invalida: '{}': {}", windowStr, e.getMessage());
            }
        }
    }

    public Map<String, Object> getAutoStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", configService.isAutoSnapshotEnabled());
        status.put("windows", configService.getAutoSnapshotWindows());

        if (lastAutoSnapshotMs > 0) {
            status.put("lastAutoSnapshot", lastAutoSnapshotMs);
            status.put("lastAutoSnapshotFormatted",
                    Instant.ofEpochMilli(lastAutoSnapshotMs).atZone(SP_ZONE).format(DT_FMT));
        }

        // Calculate next window
        String nextWindow = calculateNextWindow();
        if (nextWindow != null) {
            status.put("nextWindow", nextWindow);
        }

        return status;
    }

    private String calculateNextWindow() {
        List<String> windows = configService.getAutoSnapshotWindows();
        if (windows.isEmpty()) return null;

        LocalTime now = ZonedDateTime.now(SP_ZONE).toLocalTime();

        // Sort windows and find the next one after now
        List<LocalTime> sorted = new ArrayList<>();
        for (String w : windows) {
            try { sorted.add(LocalTime.parse(w, TIME_FMT)); }
            catch (Exception ignored) {}
        }
        sorted.sort(Comparator.naturalOrder());

        for (LocalTime wt : sorted) {
            if (wt.isAfter(now)) {
                return wt.format(TIME_FMT);
            }
        }
        // Wrap to first window of next day
        return sorted.isEmpty() ? null : sorted.get(0).format(TIME_FMT) + " (amanha)";
    }
}

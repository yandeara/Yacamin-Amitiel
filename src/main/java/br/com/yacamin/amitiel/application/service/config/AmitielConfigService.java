package br.com.yacamin.amitiel.application.service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Slf4j
public class AmitielConfigService {

    @Value("${app.auto-snapshot.enabled:true}")
    private volatile boolean autoSnapshotEnabled;

    private volatile int autoSnapshotIntervalMinutes = 5;

    public boolean isAutoSnapshotEnabled() {
        return autoSnapshotEnabled;
    }

    public void setAutoSnapshotEnabled(boolean autoSnapshotEnabled) {
        log.info("[CONFIG] autoSnapshotEnabled: {} -> {}", this.autoSnapshotEnabled, autoSnapshotEnabled);
        this.autoSnapshotEnabled = autoSnapshotEnabled;
    }

    public int getAutoSnapshotIntervalMinutes() {
        return autoSnapshotIntervalMinutes;
    }

    public void setAutoSnapshotIntervalMinutes(int minutes) {
        log.info("[CONFIG] autoSnapshotIntervalMinutes: {} -> {}", this.autoSnapshotIntervalMinutes, minutes);
        this.autoSnapshotIntervalMinutes = minutes;
    }

    public Map<String, Object> getConfigMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("autoSnapshotEnabled", autoSnapshotEnabled);
        map.put("autoSnapshotIntervalMinutes", autoSnapshotIntervalMinutes);
        return map;
    }
}

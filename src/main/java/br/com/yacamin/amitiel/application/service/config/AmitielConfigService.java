package br.com.yacamin.amitiel.application.service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AmitielConfigService {

    @Value("${app.auto-snapshot.enabled:true}")
    private volatile boolean autoSnapshotEnabled;
    private volatile List<String> autoSnapshotWindows = List.of("00:00", "06:00", "12:00", "18:00");

    public boolean isAutoSnapshotEnabled() {
        return autoSnapshotEnabled;
    }

    public void setAutoSnapshotEnabled(boolean autoSnapshotEnabled) {
        log.info("[CONFIG] autoSnapshotEnabled: {} -> {}", this.autoSnapshotEnabled, autoSnapshotEnabled);
        this.autoSnapshotEnabled = autoSnapshotEnabled;
    }

    public List<String> getAutoSnapshotWindows() {
        return autoSnapshotWindows;
    }

    public void setAutoSnapshotWindows(List<String> autoSnapshotWindows) {
        log.info("[CONFIG] autoSnapshotWindows: {} -> {}", this.autoSnapshotWindows, autoSnapshotWindows);
        this.autoSnapshotWindows = List.copyOf(autoSnapshotWindows);
    }

    public Map<String, Object> getConfigMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("autoSnapshotEnabled", autoSnapshotEnabled);
        map.put("autoSnapshotWindows", autoSnapshotWindows);
        return map;
    }
}

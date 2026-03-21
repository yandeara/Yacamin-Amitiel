package br.com.yacamin.amitiel.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "real_pnl_events")
public class RealPnlEvent {

    @Id
    private String id;

    private long timestamp;        // epoch millis
    private long marketUnixTime;   // block unix (5m block identifier)
    private String slug;

    private String outcome;        // UP or DOWN
    private String sideClose;      // TP, SL, RESOLVE
    private String status;         // CLOSED or RESOLVED

    private double entryPrice;
    private double exitPrice;
    private double pnl;
    private double size;
    private int tickCount;
    private double delta;
    private int totalFlips;
    private String algorithm;

    private boolean partialFill;      // true se o SL foi fill parcial (sobrou residual)
    private double originalSize;      // size original antes do fill parcial (0 = full fill)
}

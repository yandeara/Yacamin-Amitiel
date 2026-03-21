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
@Document(collection = "sim_pnl_events")
public class SimPnlEvent {

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

    private String algorithm;  // ALPHA, BETA, SIGMA, GAMA
}

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
@Document(collection = "wallet_snapshots")
public class WalletSnapshot {

    @Id
    private String id;

    private long timestamp;           // epoch millis

    private double usdcE;             // USDC.e balance on proxy wallet
    private double usdcNative;        // native USDC balance on proxy wallet
    private double clobBalance;       // CLOB API balance (deposited on Polymarket)
    private double totalOnChain;      // usdcE + usdcNative

    private double systemPnl;         // PnL acumulado no sistema (eventos RECONCILED) ate o momento
    private double systemPnlDelta;    // delta de PnL desde o snapshot anterior

    private double expectedBalance;   // baseBalance + systemPnlDelta
    private double actualBalance;     // clobBalance (saldo real)
    private double divergence;        // actualBalance - expectedBalance

    private boolean baseline;         // true se este e o snapshot base
    private String baselineId;        // id do snapshot base usado para comparacao
}

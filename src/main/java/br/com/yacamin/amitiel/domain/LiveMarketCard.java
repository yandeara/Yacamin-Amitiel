package br.com.yacamin.amitiel.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Card em memoria da tela de Reconciliation Live. Um por market ativo ou
 * recem-resolvido. Populacao em duas fases:
 *
 * <ul>
 *   <li><b>Subfase 1b (discovery)</b> popula: slug, marketUnixTime, displayName,
 *   conditionId, tokenUpId, tokenDownId, endUnixTime, state = OPEN.</li>
 *   <li><b>Subfase 1c (resolve handler)</b> popula: winningOutcome, buyCost,
 *   sellRevenue, totalFees, redeemPayout, pnlGabriel, divergence, state = RESOLVED.</li>
 * </ul>
 *
 * <p>Nao e persistido — vive so em {@code Map<String, LiveMarketCard>} em memoria
 * do {@code LiveMarketStateService}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveMarketCard {

    // ─── Discovery (1b) ─────────────────────────────────────────────

    private String slug;
    private long marketUnixTime;
    private long endUnixTime;
    private String displayName;
    private String conditionId;
    private String tokenUpId;
    private String tokenDownId;
    private LiveMarketState state;

    // ─── Resolve / Reconciliation (1c) ──────────────────────────────

    /** "Up" ou "Down" — preenchido quando RESOLVED */
    private String winningOutcome;

    /** Custo total de BUYs agregado pela VerificationService */
    private Double buyCost;

    /** Revenue total de SELLs agregado pela VerificationService */
    private Double sellRevenue;

    /** Fees totais (BUY + SELL) */
    private Double totalFees;

    /** Redeem payout computado pela VerificationService a partir do banco do Gabriel */
    private Double redeemPayout;

    /** PnL final que o Gabriel cravou no evento PNL. null se o evento ainda nao existir */
    private Double pnlGabriel;

    /**
     * Divergencia = pnlGabriel - (sellRevenue + redeemPayout - buyCost - totalFees).
     * Sera preenchido na subfase 1c junto com os outros campos. Na subfase 1b sempre null.
     */
    private Double divergence;
}

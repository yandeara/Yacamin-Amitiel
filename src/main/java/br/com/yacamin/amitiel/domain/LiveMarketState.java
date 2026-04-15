package br.com.yacamin.amitiel.domain;

/**
 * Estado de um {@link LiveMarketCard} na tela de Reconciliation Live.
 *
 * <p>Transicoes:
 * <ul>
 *   <li>{@code OPEN} → criado no discovery (1b), enquanto o bloco nao resolveu</li>
 *   <li>{@code RESOLVING} → evento market_resolved recebido via WS (1c), mas o
 *       Gabriel ainda nao escreveu o {@code PNL} event no Mongo — ficamos em
 *       RESOLVING ate que tudo esteja disponivel. {@code winningOutcome} ja
 *       esta preenchido neste estado.</li>
 *   <li>{@code RESOLVED} → reconciliation completa, todos os campos numericos
 *       preenchidos (buyCost, sellRevenue, totalFees, redeemPayout, pnlGabriel,
 *       divergence).</li>
 * </ul>
 */
public enum LiveMarketState {
    OPEN,
    RESOLVING,
    RESOLVED
}

package br.com.yacamin.amitiel.application.service.wallet;

import br.com.yacamin.amitiel.adapter.out.persistence.EventRepository;
import br.com.yacamin.amitiel.adapter.out.rest.polymarket.PolymarketRedeemService;
import br.com.yacamin.amitiel.domain.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orquestra o fluxo de recuperacao de dust com cadeia de eventos:
 *   DUST_REDEEM_REQUESTED → DUST_REDEEM_CONFIRMED / DUST_REDEEM_FAILED
 *
 * Apos confirmacao, re-verifica on-chain se o dust foi recuperado.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DustRedeemService {

    private final PolymarketRedeemService redeemService;
    private final EventRepository eventRepository;

    // ─── 1. Submit redeem + gravar DUST_REDEEM_REQUESTED ─────────

    public Map<String, Object> submitDustRedeem(String slug, String conditionId,
                                                  String tokenUpId, String tokenDownId, boolean negRisk) {
        long now = System.currentTimeMillis();

        // Verificar se ja existe um DUST_REDEEM_REQUESTED pendente para este slug
        List<Event> existing = eventRepository.findBySlugOrderByTimestampAsc(slug);
        boolean hasPending = false;
        for (Event e : existing) {
            if ("DUST_REDEEM_REQUESTED".equals(e.getType())) {
                // Check if there's a CONFIRMED or FAILED after it
                boolean resolved = existing.stream()
                        .filter(ev -> ev.getTimestamp() > e.getTimestamp())
                        .anyMatch(ev -> "DUST_REDEEM_CONFIRMED".equals(ev.getType())
                                || "DUST_REDEEM_FAILED".equals(ev.getType()));
                if (!resolved) {
                    hasPending = true;
                    break;
                }
            }
        }

        if (hasPending) {
            return Map.of("success", false,
                    "error", "Ja existe um redeem de dust pendente para este mercado. Use 'Verificar Status' primeiro.");
        }

        // Submit redeem
        Map<String, Object> redeemResult = redeemService.redeemDust(conditionId, tokenUpId, tokenDownId, negRisk);

        if (!Boolean.TRUE.equals(redeemResult.get("success"))) {
            return redeemResult;
        }

        String transactionId = (String) redeemResult.get("transactionId");

        // Gravar evento DUST_REDEEM_REQUESTED
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conditionId", conditionId);
        payload.put("negRisk", negRisk);
        payload.put("tokenUpId", tokenUpId);
        payload.put("tokenDownId", tokenDownId);
        payload.put("transactionId", transactionId);
        payload.put("tokenUpBalance", redeemResult.get("tokenUpBalance"));
        payload.put("tokenDownBalance", redeemResult.get("tokenDownBalance"));
        payload.put("tokenUpUsdc", redeemResult.get("tokenUpUsdc"));
        payload.put("tokenDownUsdc", redeemResult.get("tokenDownUsdc"));
        payload.put("source", "AMITIEL_DUST_RECOVERY");

        Event event = Event.builder()
                .slug(slug)
                .timestamp(now)
                .type("DUST_REDEEM_REQUESTED")
                .payload(payload)
                .build();
        eventRepository.save(event);

        log.info("[DUST] DUST_REDEEM_REQUESTED salvo: slug={}, txId={}", slug, transactionId);

        Map<String, Object> result = new LinkedHashMap<>(redeemResult);
        result.put("eventId", event.getId());
        result.put("eventType", "DUST_REDEEM_REQUESTED");
        return result;
    }

    // ─── 2. Check status + gravar CONFIRMED ou FAILED ────────────

    public Map<String, Object> checkDustRedeemStatus(String slug, String tokenUpId, String tokenDownId, boolean negRisk) {
        List<Event> events = eventRepository.findBySlugOrderByTimestampAsc(slug);

        // Encontrar o ultimo DUST_REDEEM_REQUESTED sem CONFIRMED/FAILED depois
        Event pendingRequest = null;
        for (int i = events.size() - 1; i >= 0; i--) {
            Event e = events.get(i);
            if ("DUST_REDEEM_CONFIRMED".equals(e.getType()) || "DUST_REDEEM_FAILED".equals(e.getType())) {
                break; // Ja foi resolvido
            }
            if ("DUST_REDEEM_REQUESTED".equals(e.getType())) {
                pendingRequest = e;
                break;
            }
        }

        if (pendingRequest == null) {
            return Map.of("status", "NO_PENDING", "message", "Nenhum redeem de dust pendente para este mercado");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> reqPayload = (Map<String, Object>) pendingRequest.getPayload();
        String transactionId = (String) reqPayload.get("transactionId");

        if (transactionId == null) {
            return Map.of("status", "ERROR", "message", "DUST_REDEEM_REQUESTED sem transactionId");
        }

        // Consultar estado no relayer
        String state = redeemService.getTransactionState(transactionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactionId", transactionId);
        result.put("relayerState", state);

        long now = System.currentTimeMillis();

        if ("STATE_CONFIRMED".equals(state)) {
            // Re-verificar on-chain se o dust sumiu
            Map<String, Object> balanceCheck = redeemService.queryDustBalance(tokenUpId, tokenDownId, negRisk);
            boolean dustGone = !Boolean.TRUE.equals(balanceCheck.get("hasDust"));

            // Calcular valor recuperado
            double recoveredUp = reqPayload.get("tokenUpUsdc") instanceof Number
                    ? ((Number) reqPayload.get("tokenUpUsdc")).doubleValue() : 0;
            double recoveredDown = reqPayload.get("tokenDownUsdc") instanceof Number
                    ? ((Number) reqPayload.get("tokenDownUsdc")).doubleValue() : 0;
            double recoveredValue = round4(recoveredUp + recoveredDown);

            // Gravar DUST_REDEEM_CONFIRMED
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("transactionId", transactionId);
            payload.put("redeemState", state);
            payload.put("recoveredValue", recoveredValue);
            payload.put("tokenUpRecovered", recoveredUp);
            payload.put("tokenDownRecovered", recoveredDown);
            payload.put("dustGone", dustGone);
            payload.put("remainingUpBalance", balanceCheck.get("tokenUpBalance"));
            payload.put("remainingDownBalance", balanceCheck.get("tokenDownBalance"));
            payload.put("source", "AMITIEL_DUST_RECOVERY");

            Event confirmedEvent = Event.builder()
                    .slug(slug).timestamp(now).type("DUST_REDEEM_CONFIRMED").payload(payload).build();
            eventRepository.save(confirmedEvent);

            log.info("[DUST] DUST_REDEEM_CONFIRMED: slug={}, recovered={}, dustGone={}", slug, recoveredValue, dustGone);

            result.put("status", "CONFIRMED");
            result.put("recoveredValue", recoveredValue);
            result.put("dustGone", dustGone);
            result.put("eventId", confirmedEvent.getId());
            result.put("balanceCheck", balanceCheck);

        } else if ("STATE_FAILED".equals(state) || "STATE_INVALID".equals(state)) {
            // Gravar DUST_REDEEM_FAILED
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("transactionId", transactionId);
            payload.put("redeemState", state);
            payload.put("errorMessage", "Transacao falhou on-chain: " + state);
            payload.put("source", "AMITIEL_DUST_RECOVERY");

            Event failedEvent = Event.builder()
                    .slug(slug).timestamp(now).type("DUST_REDEEM_FAILED").payload(payload).build();
            eventRepository.save(failedEvent);

            log.warn("[DUST] DUST_REDEEM_FAILED: slug={}, state={}", slug, state);

            result.put("status", "FAILED");
            result.put("errorMessage", "Transacao falhou: " + state);
            result.put("eventId", failedEvent.getId());

        } else {
            // Ainda processando
            result.put("status", "PROCESSING");
            result.put("message", "Transacao ainda em processamento: " + (state != null ? state : "sem resposta"));
        }

        return result;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Yacamin-Amitiel is a Spring Boot 4.0.3 (Java 21) monitoring, reconciliation and reporting dashboard for the Yacamin crypto trading system. It reads trading data produced by other Yacamin modules (Gabriel for real trading, Miguel for simulation, Uriel for redeems) and provides tools for CLOB trade verification, PnL reconciliation, dust recovery, wallet balance tracking, and algorithm comparison — all through a REST API + single-page frontend.

Amitiel does **not** execute trades or connect to WebSockets. It is a read-heavy, operator-facing tool.

## Documentation

Project-wide documentation lives in `D:\Projetos\Yacamin\documentos\`. When the user references "documentacao" or "documentos", always look in that folder. Key files:

- `yacamin-eventos.md` — Catalog of all MongoDB events emitted by every Yacamin module (Gabriel, Miguel, Uriel, Amitiel)
- `yacamin-configuration.md` — Configuration persistence pattern shared by all modules (trading_config collection)
- `amitiel-saldo-wallet.md` — Wallet balance monitoring, auto-snapshot scheduler, and how to read PnL/balance from latest snapshot
- `amitiel-json-exports.md` — JSON export formats (timeline, PnL chain, snapshots) for cross-module consumption
- `amitiel-erc1155-balance-query.md` — How to query ERC-1155 conditional token balances on-chain (dust detection, for Uriel replication)

## Build & Run Commands

```bash
./gradlew build          # Build
./gradlew bootRun        # Run (port 8080)
./gradlew clean build    # Clean build
```

## Architecture

**Hexagonal Architecture (Ports & Adapters)** — no event-driven processing, all operations are REST-driven.

### Layers

- **`domain/`** — MongoDB document entities: `Event`, `SimEvent`, `SimPnlEvent`, `RealPnlEvent`, `WalletSnapshot`
- **`application/service/event/`** — Event timeline & reconciliation: `EventTimelineService`, `SimEventTimelineService`, `EventHeatmapService`
- **`application/service/verification/`** — Real trade verification against CLOB: `VerificationService`
- **`application/service/wallet/`** — Wallet balance snapshots & dust recovery: `WalletBalanceService`, `DustRedeemService`
- **`application/service/algoritms/simulation/`** — PnL querying & comparison: `SimPnlQueryService`
- **`application/service/auth/`** — BCrypt password authentication: `AuthService`, `AuthFilter`
- **`application/configuration/`** — Spring beans: Jackson, async thread pools, auth filter registration
- **`adapter/out/rest/polymarket/`** — Outbound REST: `PolymarketClobClient` (CLOB API), `PolymarketGammaClient` (Gamma API), `PolymarketRedeemService` (Relayer API), `ClobAuthSigner` + `EIP712Signer` (auth)
- **`adapter/out/persistence/`** — MongoDB repositories: `EventRepository`, `SimEventRepository`, `SimPnlEventRepository`, `RealPnlEventRepository`, `WalletSnapshotRepository`
- **`adapter/in/controller/`** — REST API: `DashboardController` (all endpoints), `AuthController` (login/logout)

### Database (MongoDB)

Amitiel connects to the same MongoDB as Gabriel/Miguel/Uriel.

**Collections READ (from other modules):**

| Collection | Document Class | Written by | Usage |
|------------|---------------|------------|-------|
| `events` | `Event` | Gabriel, Uriel | Timeline de mercados reais, reconciliacao, calculo de systemPnl |
| `sim_events` | `SimEvent` | Miguel | Timeline de mercados simulados por algoritmo |
| `sim_pnl_events` | `SimPnlEvent` | Miguel | PnL por periodos, heatmap, comparacao de algoritmos |
| `real_pnl_events` | `RealPnlEvent` | Gabriel | Verificacao de mercados reais, listagem por data |

**Collections WRITTEN by Amitiel:**

| Collection | Document Class | Events Written |
|------------|---------------|----------------|
| `events` | `Event` | `FEES`, `PNL`, `RECONCILED`, `DUST`, `DUST_REDEEM_REQUESTED`, `DUST_REDEEM_CONFIRMED`, `DUST_REDEEM_FAILED` |
| `wallet_snapshots` | `WalletSnapshot` | Wallet balance snapshots (on-chain + CLOB + systemPnl) |

All events written by Amitiel use `source: "AMITIEL_RECONCILIATION"` or `source: "AMITIEL_DUST_RECOVERY"` in the payload to distinguish from Gabriel's events.

**Cross-module modification:** Amitiel can modify Uriel's `REDEEM_CONFIRMED` events (adding `correctedByAmitiel: true`) when dust false positive is detected.

### REST API Endpoints

**PnL & Heatmap:**
- `GET /api/dashboard/sim-pnl?mode=sim&algorithm=ALPHA` — PnL by periods (15m-24h)
- `GET /api/dashboard/sim-pnl/heatmap?year=2026&month=3&mode=sim&algorithm=ALPHA` — PnL heatmap
- `GET /api/dashboard/sim-comparison` — All algorithms PnL side-by-side
- `GET /api/dashboard/heatmap` — Unified heatmap (PnL + flips)

**Verification (Real Trading):**
- `GET /api/dashboard/verification/markets?date=2026-03-21&hourFrom=0&hourTo=23` — Real markets with pagination
- `GET /api/dashboard/verification/detail?marketUnixTime=...` — Market detail (DB-only)
- `POST /api/dashboard/verification/verify?marketUnixTime=...` — Reconcile with CLOB API

**Events Timeline (Real Trading):**
- `GET /api/dashboard/events/markets?date=2026-03-21&filter=pending` — Event markets, filterable for pending reconciliation
- `GET /api/dashboard/events/timeline?slug=...` — Full event timeline for a slug
- `POST /api/dashboard/events/verify?slug=...` — Verify events against CLOB trades
- `POST /api/dashboard/events/accept` — Accept reconciliation (creates FEES/PNL/RECONCILED events)

**Simulation Events:**
- `GET /api/dashboard/sim-events/markets?date=2026-03-21&algorithm=ALPHA` — Sim markets by algorithm
- `GET /api/dashboard/sim-events/timeline?slug=...&algorithm=ALPHA` — Sim event timeline

**Event Heatmap:**
- `GET /api/dashboard/event-heatmap?mode=sim&algorithm=ALPHA` — PnL heatmap from events collection

**Wallet:**
- `GET /api/dashboard/wallet/snapshots` — List all wallet snapshots
- `POST /api/dashboard/wallet/snapshot` — Take new snapshot (on-chain + CLOB + systemPnl)
- `POST /api/dashboard/wallet/reset-baseline` — Reset baseline for divergence tracking

**Dust Recovery:**
- `POST /api/dashboard/dust/redeem` — Submit dust recovery to Polymarket Relayer
- `POST /api/dashboard/dust/check` — Check redeem status via Relayer polling
- `POST /api/dashboard/dust/query` — Query ERC-1155 dust balance on-chain

**Auth:**
- `POST /login` — BCrypt password authentication
- `POST /api/logout` — Logout

### Frontend

Single-page app (`index.html`) with these views:
1. **PnL History** — Period cards (15m-24h) with mode (SIM/REAL) and algorithm selector
2. **PnL Heatmap** — Hour x day grid with mode, algorithm, and hour/day range filters
3. **Comparar Algoritmos** — Table comparing all algorithms across periods (PnL, trades, win rate)
4. **Verificacao** — Real market verification against CLOB API trades
5. **Events Timeline** — Full event timeline for real markets with reconciliation workflow
6. **Sim Events Timeline** — Simulation event timeline per algorithm
7. **Event Heatmap** — PnL heatmap from events collection
8. **Wallet** — Balance snapshots with baseline divergence tracking
9. **Dust Recovery** — ERC-1155 dust balance query and redemption

### Polymarket Authentication

**L1 (EIP-712)** — Credential creation/derivation:
- Domain `ClobAuthDomain`, version `1`, chainId `137` (Polygon)
- Headers use **underscores**: `POLY_ADDRESS`, `POLY_SIGNATURE`, `POLY_TIMESTAMP`, `POLY_NONCE`
- Always uses **server timestamp** (from `/time`) to avoid clock skew
- Uses **EIP-55 checksummed addresses**

**L2 (HMAC-SHA256)** — Authenticated API calls:
- Message: `timestamp + method + path` (path **without** query string)
- Secret: Base64 URL-safe decoded; signature: Base64 **URL-safe** encoded
- Additional headers: `POLY_API_KEY`, `POLY_PASSPHRASE`

### Wallet Addresses

Two distinct addresses exist in the Polymarket system:
- **Signer (EOA)** — Derived from private key. Used for EIP-712 signing.
- **Proxy wallet** — Smart contract wallet (`wallet-address` in YAML). Where funds reside. Used for on-chain balance queries.

### Thread Model

Minimal — Amitiel is request-driven:
- `pnlQueryExecutor` (pool 1-2) — Async heatmap queries

### External Integrations

- **Polymarket CLOB API** (`clob.polymarket.com`) — Trade history, balance queries (authenticated)
- **Gamma API** (`gamma-api.polymarket.com/markets`) — Market metadata (conditionId, tokenIds)
- **Polymarket Relayer API** (`relayer-v2.polymarket.com`) — Dust redeem transactions (gasless)
- **Polygon RPC** (`polygon-bor-rpc.publicnode.com`) — On-chain ERC-20/ERC-1155 balance queries
- **MongoDB** — Shared with Gabriel/Miguel/Uriel

## Key Dependencies

- **web3j** (`5.0.2`) — Ethereum signing, Keccak-256, address utilities, ERC-1155 balance queries
- **Jackson** — UTC timezone, ISO-8601 format, custom deserializers
- **Lombok** — `@Data`, `@Builder`, `@Slf4j` throughout
- **Spring Data MongoDB** — Repository pattern for all collections
- **Spring Security Crypto** — BCrypt password hashing for dashboard auth

## Configuration

All secrets and endpoints are in `application.yaml` under `polymarket.*`. Values with `0x` prefix (addresses, private keys) **must be quoted** in YAML to prevent hex number interpretation.

### AlgoCalc Enum

`AlgoCalc` defines the algorithm identifiers used for filtering: `ALPHA`, `GAMA`. Used in queries to `sim_pnl_events` and `sim_events` collections.

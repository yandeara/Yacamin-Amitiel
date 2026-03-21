# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Yacamin-Gabriel is a Spring Boot 4.0.3 (Java 21) crypto trading signal engine that combines Polymarket prediction markets with Binance spot market data to generate probabilistic HFT-5m (5-minute block) trading signals for BTC UP/DOWN markets. It supports multiple probability algorithms running in parallel simulation to compare performance before deploying to real trading.

## Build & Run Commands

```bash
./gradlew build          # Build
./gradlew bootRun        # Run (port 8080)
./gradlew clean build    # Clean build
```

No test framework is configured.

## Architecture

**Hexagonal Architecture (Ports & Adapters)** with event-driven async processing.

### Layers

- **`domain/`** — Pure business entities: `Market`, `PolyAsset`, `BlockState`, `PricePoint`, `EntryRecord`, `SimPnlEvent`
- **`application/service/algoritms/`** — Algorithm framework: `BookTickerCalculation` interface, `AlgoCalc` enum, `AlgorithmRegistry`, and 4 algorithm implementations (alpha, beta, sigma, gama)
- **`application/service/algoritms/simulation/`** — Multi-algorithm simulation pipeline: market memory, wallet, open/close/resolve services, PnL persistence and queries — all parameterized by `AlgoCalc`
- **`application/service/trading/`** — Real trading pipeline: `MarketMemoryService`, `OnBookTickerService`, `OnPriceChangeUseCase`, `OnResolveUseCase`
- **`application/service/usecase/`** — Shared use cases: `CalculateFiveMinUnixUseCase`
- **`application/configuration/`** — Spring beans: Jackson, OkHttp, RestTemplate, async thread pools
- **`adapter/out/rest/`** — Outbound REST: Polymarket auth, Gamma API markets client
- **`adapter/out/websocket/`** — Outbound WebSockets: Polymarket CLOB + Binance Spot
- **`adapter/out/persistence/`** — MongoDB repositories: `SimPnlEventRepository`, `RealPnlEventRepository`
- **`adapter/in/event/`** — Inbound event listeners (Spring `@EventListener` + `@Async`)
- **`adapter/in/controller/`** — REST API: `DashboardController` with market data, PnL, heatmap, and algorithm comparison endpoints

### Multi-Algorithm Framework

The system supports 2 probability calculation algorithms, defined in `AlgoCalc` enum (order: ALPHA, GAMA):

- **`BookTickerCalculation`** — Common interface with `calculate(BookTickerUpdateResponse)` and `getAlgo()`
- **`TickResult`** — Shared record: `(blockUnix, pSuccess, delta, distance, sigma)`
- **`AlgorithmRegistry`** — Spring service that collects all `BookTickerCalculation` beans into `Map<AlgoCalc, BookTickerCalculation>`

**Algorithm implementations** (each in its own sub-package under `algoritms/`):

| Algorithm | Package | Description |
|-----------|---------|-------------|
| **Alpha** | `algoritms/alpha/` | Simple volatility EMA (alpha=0.1) + Gaussian diffusion. Single 60s window. |
| **Gama** | `algoritms/gama/` | Aggressive momentum-first. Higher drift/accel weights (1.35/1.60). Stronger deceleration penalty (1.90). |

Each algorithm maintains its own `BlockState` map independently. All share the same base formula: `pSuccess = 1 - exp(-2 * distance² / (sigma² * timeRemaining))` with algorithm-specific distance/sigma calculations.

### Data Flows

**Market Discovery (hourly, 14 markets = 70 min ahead):**
`PolymarketMarketClobWsAdapter` → Gamma API (`/markets/slug/btc-updown-5m-{unix}`) → extract token IDs → creates 1 real market + 2 sim markets (one per algorithm) in respective `MarketMemoryService` → subscribe via WebSocket

**Tick Processing (real-time, decoupled):**
Binance bookTicker → `SpotMarketDataSocket` → `BookTickerUpdateSocketEvent` → `BinanceListenerAdapter`:
1. All algorithms calculate independently via `AlgorithmRegistry` (sequential, in-memory)
2. **Real pipeline**: Alpha result → `OnBookTickerService` → updates real `Market`
3. **Sim pipeline**: Each algorithm's result → `SimulationOnBookTickerService.updateMarket(algo, result)` → updates that algorithm's sim `Market`
4. All dispatched in parallel via `priceChangeWorkerExecutor` (pool size 6)

**Simulation Pessimism (realistic execution modeling):**
- **Size**: Budget of 1.1 USDC relative to buy price: `size = 1.1 / ask`. Example: ask=0.50 → size=2.2 shares.
- **5s execution delay**: When opening/closing, position enters OPENING/CLOSING state. The actual entry/exit price is taken from the BID/ASK 5 seconds later, simulating real order execution latency.
- **PnL with size**: All PnL calculations use `(exitPrice - entryPrice) * size`.

**Important**: Real and sim pipelines are fully decoupled. If Alpha returns null (block transitioning), other algorithms can still process their sim markets.

**Polymarket Price Events (real-time):**
`PolyMarketWsMarketListener.onPriceChange()` → dispatches to real pipeline + all sim pipelines in parallel → each sim pipeline independently opens/closes positions based on its own algorithm's pSuccess/delta

**Market Resolution:**
`PolyMarketWsMarketListener.onMarketResolve()` → dispatches to real + all sim pipelines → positions settled at 1.0 (won) or 0.0 (lost) → PnL persisted to MongoDB with `algorithm` field

**Time Remaining (independent, every 1 second):**
`@Scheduled(fixedRate = 1_000)` in both `MarketMemoryService` and `SimulationMarketMemoryService` updates `timeRemaining = (unixTime + 300) - now` for all markets whose block has started (`unixTime <= now`). This runs independently of Binance ticks.

**Reconnection:**
WebSocket closes → reconnect event published → async listener → re-subscribe all streams

### Simulation Pipeline (per-algorithm isolation)

All simulation services live in `application/service/algoritms/simulation/` and are parameterized with `AlgoCalc`:

- **`SimulationMarketMemoryService`** — `Map<AlgoCalc, NavigableMap<Long, Market>>`. Each algorithm has its own isolated market state, entry history, and position tracking.
- **`SimulationWalletMemoryService`** — `Map<AlgoCalc, Wallet>`. Per-algorithm cumulative PnL. Initialized from last 24h MongoDB data (treats records with `algorithm=null` as ALPHA for backward compat).
- **`SimulationOnBookTickerService`** — Updates sim market metrics (pSuccess, delta, distance, sigma, tickCount) for a specific algorithm.
- **`SimulationOnPriceChangeService`** — Handles Polymarket price events for a specific algorithm's markets → delegates to open/close services.
- **`SimulationOpenPositionService`** — Opens positions when: `pSuccess >= 0.85`, `timeRemaining` 10-240s, delta direction matches outcome. Skips startup block.
- **`SimulationClosePositionService`** — Closes on delta reversal with 1s confirmation delay (SL/TP).
- **`SimulationOnResolveService`** — Settles positions on market resolution (UP/DOWN win).
- **`SimPnlPersistenceService`** — Async MongoDB writes with `algorithm` field. Uses `mongoWriteExecutor`.
- **`SimPnlQueryService`** — Period-based PnL (15m-24h) and heatmap (hour×day grid) queries, filterable by algorithm. `getComparisonPnl()` returns all algorithms side-by-side.

### Database (MongoDB)

- **`sim_pnl_events`** — Simulation trade events. Fields: `id`, `timestamp`, `marketUnixTime`, `slug`, `outcome` (UP/DOWN), `sideClose` (TP/SL/RESOLVE), `status` (CLOSED/RESOLVED), `entryPrice`, `exitPrice`, `pnl`, `size`, `tickCount`, `algorithm` (ALPHA/GAMA).
- **`real_pnl_events`** — Same structure for real trades.

Backward compat: existing records without `algorithm` field are treated as ALPHA.

### Dashboard (REST API + Single-Page Frontend)

**API Endpoints:**
- `GET /api/dashboard/markets?mode=sim&algorithm=ALPHA` — Market data with algorithm filter for sim mode
- `GET /api/dashboard/sim-pnl?mode=sim&algorithm=ALPHA` — PnL by periods (15m-24h) filtered by algorithm
- `GET /api/dashboard/sim-pnl/heatmap?year=2026&month=3&mode=sim&algorithm=ALPHA` — PnL heatmap filtered by algorithm
- `GET /api/dashboard/sim-comparison` — All algorithms PnL side-by-side for comparison
- `POST /api/dashboard/trading/toggle` — Toggle real trading on/off

**Frontend pages** (`index.html` single-page app):
1. **Dashboard Real** — Real market cards, positions, PnL
2. **Dashboard Simulado** — Sim market cards with algorithm selector (ALPHA|BETA|SIGMA|GAMA pills)
3. **PnL History** — Period cards (15m-24h) with mode (SIM/REAL) and algorithm selector
4. **PnL Heatmap** — Hour×day grid with mode, algorithm selector, and hour/day range filters
5. **Comparar Algoritmos** — Table comparing all 4 algorithms across all periods (PnL, trades, win rate). Best performer highlighted.

Dashboard filters future markets: only shows blocks that have started (`unixTime <= now`) or have entry history.

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
- **Signer (EOA)** — Derived from private key (Magic Link). Used for EIP-712 signing.
- **Proxy wallet** — Smart contract wallet (`wallet-address` in YAML). Where funds actually reside. Used for on-chain balance queries.

### Thread Model

Single-thread executors for ordered event processing:
- `bookTickUpdateListenerExecutor` — Binance book ticker events
- `polyOnPriceChangeExecutor` — Polymarket price change events
- `polyOnResolveExecutor` — Market resolution events
- `reconnectMarketDataSocketExecutor` / `reconnectPolyExecutor` — WebSocket reconnects
- `subMessageMarketDataSocketExecutor` — Binance subscription confirmations

Pool executors:
- `priceChangeWorkerExecutor` (pool 6) — Parallel dispatch of real + 4 sim algorithm pipelines
- `mongoWriteExecutor` (pool 1-2) — Async PnL persistence
- `pnlQueryExecutor` (pool 1-2) — Async heatmap queries
- `marketDiscoveryExecutor` (pool 4-8) — Parallel Gamma API calls for market discovery

Thread-safe collections throughout: `ConcurrentSkipListMap` (markets), `ConcurrentHashMap` (assets/blocks), `ConcurrentLinkedDeque` (price history), `EnumMap` (algorithm registries).

### External Integrations

- **Polymarket CLOB API** (`clob.polymarket.com`) — Auth, balance, order book
- **Polymarket WebSocket** (`ws-subscriptions-clob.polymarket.com`) — Real-time market data (dual-channel: market + user)
- **Gamma API** (`gamma-api.polymarket.com/markets`) — Market metadata by slug
- **Binance Spot WebSocket** (`stream.binance.com:9443/ws`) — bookTicker, kline, trade streams
- **Polygon RPC** (`polygon-bor-rpc.publicnode.com`) — On-chain ERC-20 balance queries (USDC.e + native USDC)
- **MongoDB** — PnL event persistence (sim + real)

## Key Dependencies

- **web3j** (`5.0.2`) — Ethereum signing, Keccak-256, address utilities
- **OkHttp3** (`4.12.0`) — Polymarket WebSocket + async REST
- **Java-WebSocket** (`1.6.0`) — Binance WebSocket client
- **Jackson** — UTC timezone, ISO-8601 format, custom deserializers for Polymarket responses
- **Lombok** — `@Data`, `@Builder`, `@Slf4j` throughout
- **Spring Data MongoDB** — Repository pattern for PnL events

## Configuration

All secrets and endpoints are in `application.yaml` under `polymarket.*` and `binance.*`. Values with `0x` prefix (addresses, private keys) **must be quoted** in YAML to prevent hex number interpretation.

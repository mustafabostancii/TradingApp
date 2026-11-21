# Funding Spread — Android

Watch the funding-rate gap between a coin's **USDT / USDC / USD1** perpetuals on
**MEXC, Binance, OKX and Bybit**.

You type a base coin (`BTC`, not `BTCUSDT`). The app finds every quote variant of
it that the selected exchange actually lists, shows each one's current funding
rate and its own countdown, and headlines the **largest absolute gap** between
any two of them. With three quotes present all three pairs are compared
(USDT/USDC, USDT/USD1, USDC/USD1) and the widest wins. Everything refreshes
every 10 seconds; countdowns tick every second.

---

## Build it

**With Android Studio (easiest)**

1. Android Studio → *Open* → pick this `android` folder.
2. Wait for the Gradle sync (first run downloads Gradle and the AndroidX
   libraries — a few minutes and about 1.5 GB).
3. Plug the phone in with USB debugging on, then press **Run ▶**.

**From the command line**

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Copy that APK to the phone and open it (allow "install from unknown sources").
Needs a JDK 17+ and `ANDROID_HOME` pointing at an SDK with API 35.

Run the off-device checks:

```bash
./gradlew :app:testDebugUnitTest
```

Requirements: minSdk 26 (Android 8.0), targetSdk 35, Kotlin 2.0.21, AGP 8.7.3.
Only permission used is `INTERNET`. No account, no API key — every endpoint is
public market data.

---

## How the numbers are produced

Getting these values *right* was the point, so the rules are explicit.

**Symbols are never built from strings.** Each exchange's instruments endpoint is
read and contracts are matched on the exchange's own `baseCoin`/`quoteCoin`
metadata. This matters: Bybit's USDC perpetual for BTC is called **`BTCPERP`**,
not `BTCUSDC`. Concatenating `base + quote` would silently request a market that
doesn't exist — or worse, a different one. The instrument list is cached for 30
minutes and re-read immediately when you add a coin it hasn't seen.

**Countdowns run on the exchange's clock, not the phone's.** Every response
carries a server timestamp (`time`, `timestamp`, `ts`). The app measures
`server − device` on each poll and applies that offset, so a phone with a drifting
clock still shows the correct time to settlement.

**Funding intervals are shown, and mismatches are flagged.** A 0.01% gap between a
4h contract and an 8h contract is not the same trade as the same gap between two
8h contracts. Each leg shows its own interval and the card warns when they
differ. The headline number stays the raw absolute difference.

**Nothing is guessed.** A leg whose rate failed to load shows `—`; a coin with
only one quote shows no gap and says why. A failed exchange call surfaces an
error banner rather than silently reusing stale numbers as if they were fresh.

### Endpoints

| Exchange | Instruments | Rates | Notes |
|---|---|---|---|
| MEXC | `/api/v1/contract/detail` | `/api/v1/contract/funding_rate` | One call covers every contract. `collectCycle` is the interval in hours. |
| Binance | `/fapi/v1/exchangeInfo` + `/fapi/v1/fundingInfo` | `/fapi/v1/premiumIndex` | No symbol ⇒ all contracts, weight 10. `fundingInfo` lists only symbols off the 8h default. |
| OKX | `/api/v5/public/instruments?instType=SWAP` | `/api/v5/public/funding-rate?instId=…` | No batch form, so calls are fanned out 6 at a time to stay under 20 req/2s. Interval derived as `fundingTime − prevFundingTime`. |
| Bybit | `/v5/market/instruments-info?category=linear` | `/v5/market/tickers?category=linear` | Instruments are paginated via `nextPageCursor`. `fundingInterval` is in minutes. |

The rate shown is in every case the **current rate for the upcoming
settlement** — the figure the exchange's own ticker displays next to its
countdown, not the rate that was already charged.

---

## Known limitation: OKX

At the time this was built, OKX lists **no USDC or USD1 perpetual swaps** — only
USDT-quoted ones plus coin-margined `-USD-SWAP` contracts, which are a different
instrument type and are deliberately excluded. `BTC-USDC-SWAP` and
`BTC-USD1-SWAP` both return *"Instrument ID … doesn't exist"*. OKX did list
USDC perpetuals back in 2022 and has since removed them.

So on the OKX tab most coins will show a single leg and no gap. That is the
exchange's reality, not a bug, and the app says so in a banner rather than
leaving you guessing. Nothing is hardcoded: instrument lists are read live, so if
OKX relists USDC or USD1 perpetuals the app picks them up on its own. The same
holds if MEXC, Binance or Bybit add new quote pairs.

---

## What was verified, and what wasn't

Verified — `app/src/test` covers 12 test cases over the parsing and spread layer,
built on recorded exchange payloads (the OKX block is a verbatim capture of a
live response):

- all three quotes resolved and paired; the widest of the three pairs wins
- Bybit `BTCPERP` resolution and cursor pagination
- delisted, pre-launch, dated-future and coin-margined contracts filtered out
- per-symbol funding intervals; mismatched schedules flagged
- server-clock anchoring, and missing rates never turned into a fake zero
- formatting under a Turkish locale (a decimal comma here would be wrong)

**Not verified:** the Compose UI layer, and live responses from Binance and Bybit
— the machine this was written on has no route to those two hosts, so their
parsers were built from the official API reference and tested against payloads
shaped to it. MEXC and OKX responses were captured live. First run on the phone
is the real test; if a tab errors, the message names the exchange and the cause.

---

## Where to extend

- `Model.kt` — quotes, exchanges, and the spread maths (`Spread.best`). Adding a
  fourth quote currency is one line in `Quote`.
- `Exchanges.kt` — one object per exchange behind `ExchangeClient`. A new
  exchange means one new object plus an entry in `Clients`.
- `MainViewModel.kt` — polling cadence (`REFRESH_SECONDS`), instrument cache TTL,
  watchlist persistence.
- `MainActivity.kt` — the whole UI.

Natural next steps: a threshold alert with a notification, sorting the list by
gap size, a per-coin history sparkline, or cross-exchange comparison of the same
contract.

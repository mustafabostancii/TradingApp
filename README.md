# Funding Spread
### Real-Time Cryptocurrency Funding Rate Monitor for Android

Funding Spread is an Android application designed to monitor funding rate differences across USDT, USDC, and USD1 perpetual futures markets on multiple cryptocurrency exchanges.

The application collects live market data from **MEXC, Binance, OKX, and Bybit**, compares funding rates, and identifies the largest spread between available quote currencies for a selected cryptocurrency.

The goal is to make funding rate discrepancies easier to observe through a single interface with continuously updated market data.

## Key Features

- **Multi-Exchange Support:** Fetches funding rate data from MEXC, Binance, OKX, and Bybit.
- **Multi-Quote Comparison:** Compares USDT, USDC, and USD1 perpetual contracts.
- **Automatic Spread Detection:** Identifies the largest absolute funding rate difference between available quote pairs.
- **Real-Time Updates:** Refreshes market data every 10 seconds.
- **Live Settlement Countdown:** Updates funding settlement countdowns every second.
- **Exchange Clock Synchronization:** Uses exchange server timestamps to improve countdown accuracy.
- **Funding Interval Awareness:** Displays individual funding intervals and flags mismatched settlement schedules.
- **Dynamic Market Discovery:** Reads exchange instrument metadata instead of assuming contract symbols.
- **Error Handling:** Displays missing data and exchange errors instead of silently presenting stale or misleading values.

## Supported Exchanges

| Exchange | Supported Markets | Data Source |
|---|---|---|
| MEXC | USDT / USDC / USD1 | Public Contract API |
| Binance | USDT / USDC / USD1 | Public Futures API |
| OKX | USDT | Public Swap API |
| Bybit | USDT / USDC / USD1 | Public Linear API |

Market availability depends on each exchange's actual listings. The application discovers available instruments dynamically.

## How It Works

1. The user enters a base cryptocurrency symbol, such as `BTC`.
2. The application retrieves available perpetual instruments from the selected exchange.
3. Available USDT, USDC, and USD1 contracts are identified using exchange-provided instrument metadata.
4. Current funding rates and settlement times are collected.
5. The application compares all available quote-currency pairs.
6. The pair with the largest absolute funding rate difference is displayed.

When three quote currencies are available, the application evaluates all three possible combinations:

- USDT / USDC
- USDT / USD1
- USDC / USD1

The application also accounts for differences in funding intervals and exchange server times.

## Technical Architecture

The application is developed natively for Android using Kotlin.

| Component | Technology |
|---|---|
| Platform | Android |
| Programming Language | Kotlin |
| Build System | Gradle |
| Build Configuration | Kotlin DSL |
| Market Data | Public Exchange REST APIs |
| Minimum Android Version | Android 8.0 (API 26) |
| Target Android Version | API 35 |

### Project Structure

```text
app/
 └── src/
     ├── main/
     │   ├── java/com/fundingspread/app/
     │   │   ├── Exchanges.kt
     │   │   ├── Http.kt
     │   │   ├── MainActivity.kt
     │   │   ├── MainViewModel.kt
     │   │   └── Model.kt
     │   ├── res/
     │   └── AndroidManifest.xml
     └── test/
```

### Core Components

- **Exchanges.kt:** Handles exchange-specific market data retrieval and instrument discovery.
- **Http.kt:** Provides HTTP communication functionality.
- **MainActivity.kt:** Contains the application's user interface.
- **MainViewModel.kt:** Manages application state, periodic updates, and watchlist behavior.
- **Model.kt:** Defines market data models and funding spread calculations.

## Accuracy and Data Handling

Several implementation details are important for reliable market comparisons:

**Instrument Resolution**

Trading symbols are resolved using exchange-provided instrument metadata rather than simple string concatenation. This avoids incorrect market identification, particularly when exchanges use different naming conventions.

**Funding Countdown Synchronization**

The application calculates the difference between exchange server time and device time. This offset is used to keep settlement countdowns aligned with exchange timestamps.

**Funding Interval Differences**

Funding intervals are displayed individually. When two contracts have different settlement schedules, the application flags the mismatch so users can distinguish between comparable and non-comparable funding periods.

**Missing Data Handling**

Unavailable funding rates are not converted into artificial zero values. Failed requests and missing market data are explicitly represented in the interface.

## Building the Project

### Requirements

- Android Studio
- JDK 17 or newer
- Android SDK API 35
- Internet connection

### Run with Android Studio

1. Clone this repository.
2. Open the `android` project directory in Android Studio.
3. Wait for Gradle synchronization.
4. Connect an Android device with USB debugging enabled.
5. Run the application.

### Build from the Command Line

```bash
./gradlew assembleDebug
```

The generated APK will be available at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Run Unit Tests

```bash
./gradlew :app:testDebugUnitTest
```

## Testing and Verification

The project includes unit tests covering the core funding spread and market data parsing logic.

The existing test suite contains 12 test cases covering:

- Funding rate comparison across three quote currencies.
- Selection of the largest available spread.
- Exchange-specific instrument resolution.
- Bybit instrument pagination.
- Contract filtering.
- Funding interval differences.
- Exchange server clock synchronization.
- Missing funding rate handling.
- Locale-sensitive number formatting.

**Testing limitations:** The Compose UI layer has not been verified. Live Binance and Bybit responses have not been independently verified in the development environment; their parsers were tested against payloads based on the official API documentation. MEXC and OKX responses were captured live.

## Current Limitations

OKX currently provides limited quote-currency coverage for this application's comparison model. Its supported USDT perpetual markets can be monitored, but unavailable USDC and USD1 perpetual instruments cannot produce a funding spread.

The application reads available instruments dynamically, so newly listed markets can be discovered without hardcoding their symbols.

## Future Improvements

Potential future developments include:

- Configurable funding spread alerts.
- Android push notifications.
- Historical funding spread charts.
- Spread-based market sorting.
- Additional exchange integrations.
- Cross-exchange funding rate comparisons.

## Security and Permissions

- Uses public exchange market data endpoints.
- Does not require user registration.
- Does not require private exchange API credentials.
- Requires internet access to retrieve market data.

---

**Disclaimer:** This application is a market monitoring and analytical tool. Funding rate differences do not guarantee profitable arbitrage opportunities. Trading involves market, execution, liquidity, and funding-related risks.
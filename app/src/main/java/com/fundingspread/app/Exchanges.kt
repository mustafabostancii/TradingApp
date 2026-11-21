package com.fundingspread.app

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

interface ExchangeClient {
    val exchange: Exchange

    /** Every USDT / USDC / USD1 perpetual the exchange currently lists. */
    suspend fun instruments(): Instruments

    /** Current funding readings, keyed by the exchange's own symbol. */
    suspend fun rates(symbols: List<String>): Map<String, Snapshot>
}

private fun MutableMap<String, MutableMap<Quote, Contract>>.addContract(
    base: String,
    quote: Quote,
    contract: Contract
) {
    if (base.isEmpty() || contract.symbol.isEmpty()) return
    getOrPut(base) { LinkedHashMap() }[quote] = contract
}

/* ------------------------------------------------------------------ *
 * MEXC - contract.mexc.com
 * One call returns funding data for every contract, so a refresh costs
 * a single request no matter how long the watchlist is.
 * ------------------------------------------------------------------ */
object MexcClient : ExchangeClient {

    override val exchange = Exchange.MEXC
    private const val HOST = "https://contract.mexc.com"

    override suspend fun instruments(): Instruments =
        parseInstruments(Http.obj("$HOST/api/v1/contract/detail"))

    override suspend fun rates(symbols: List<String>): Map<String, Snapshot> =
        parseRates(Http.obj("$HOST/api/v1/contract/funding_rate"))

    internal fun parseInstruments(json: JSONObject): Instruments {
        val data = json.optJSONArray("data") ?: throw HttpError("unexpected MEXC payload")
        val out = HashMap<String, MutableMap<Quote, Contract>>()
        for (i in 0 until data.length()) {
            val c = data.optJSONObject(i) ?: continue
            if (c.optInt("futureType", -1) != 1) continue   // 1 = perpetual
            if (c.optInt("state", -1) != 0) continue        // 0 = enabled
            val quote = Quote.from(c.str("quoteCoin")) ?: continue
            out.addContract(c.str("baseCoin").uppercase(), quote, Contract(c.str("symbol"), null))
        }
        return out
    }

    internal fun parseRates(json: JSONObject): Map<String, Snapshot> {
        val data = json.optJSONArray("data") ?: throw HttpError("unexpected MEXC payload")
        val out = HashMap<String, Snapshot>(data.length())
        for (i in 0 until data.length()) {
            val d = data.optJSONObject(i) ?: continue
            val symbol = d.str("symbol")
            if (symbol.isEmpty()) continue
            out[symbol] = Snapshot(
                rate = d.dbl("fundingRate"),
                nextFundingMs = d.lng("nextSettleTime"),
                serverMs = d.lng("timestamp"),
                intervalHours = d.dbl("collectCycle")
            )
        }
        return out
    }
}

/* ------------------------------------------------------------------ *
 * Binance USDS-M futures - fapi.binance.com
 * premiumIndex without a symbol returns every contract (request weight
 * 10 of the 2400/min budget), so one call per refresh is plenty.
 * ------------------------------------------------------------------ */
object BinanceClient : ExchangeClient {

    override val exchange = Exchange.BINANCE
    private const val HOST = "https://fapi.binance.com"

    override suspend fun instruments(): Instruments {
        // Only symbols with a non-standard schedule appear in fundingInfo;
        // everything else settles on the 8h default. Treated as optional
        // enrichment so a failure here cannot break the whole screen.
        val custom = try {
            parseFundingInfo(Http.arr("$HOST/fapi/v1/fundingInfo"))
        } catch (_: Exception) {
            emptyMap<String, Double>()
        }
        return parseInstruments(Http.obj("$HOST/fapi/v1/exchangeInfo"), custom)
    }

    override suspend fun rates(symbols: List<String>): Map<String, Snapshot> =
        parseRates(Http.arr("$HOST/fapi/v1/premiumIndex"))

    internal fun parseFundingInfo(info: JSONArray): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (i in 0 until info.length()) {
            val o = info.optJSONObject(i) ?: continue
            val hours = o.dbl("fundingIntervalHours") ?: continue
            val symbol = o.str("symbol")
            if (symbol.isNotEmpty()) out[symbol] = hours
        }
        return out
    }

    internal fun parseInstruments(json: JSONObject, custom: Map<String, Double>): Instruments {
        val data = json.optJSONArray("symbols") ?: throw HttpError("unexpected Binance payload")
        val out = HashMap<String, MutableMap<Quote, Contract>>()
        for (i in 0 until data.length()) {
            val s = data.optJSONObject(i) ?: continue
            if (s.str("contractType") != "PERPETUAL") continue
            if (s.str("status") != "TRADING") continue
            val quote = Quote.from(s.str("quoteAsset")) ?: continue
            val symbol = s.str("symbol")
            out.addContract(
                s.str("baseAsset").uppercase(),
                quote,
                Contract(symbol, custom[symbol] ?: 8.0)
            )
        }
        return out
    }

    internal fun parseRates(data: JSONArray): Map<String, Snapshot> {
        val out = HashMap<String, Snapshot>(data.length())
        for (i in 0 until data.length()) {
            val d = data.optJSONObject(i) ?: continue
            val symbol = d.str("symbol")
            if (symbol.isEmpty()) continue
            out[symbol] = Snapshot(
                rate = d.dbl("lastFundingRate"),
                nextFundingMs = d.lng("nextFundingTime"),
                serverMs = d.lng("time"),
                intervalHours = null
            )
        }
        return out
    }
}

/* ------------------------------------------------------------------ *
 * OKX - www.okx.com
 * The funding-rate endpoint takes one instrument at a time, so requests
 * are fanned out with a small semaphore to stay inside 20 req / 2s.
 * ------------------------------------------------------------------ */
object OkxClient : ExchangeClient {

    override val exchange = Exchange.OKX
    private const val HOST = "https://www.okx.com"
    private const val MAX_PARALLEL = 6

    override suspend fun instruments(): Instruments =
        parseInstruments(Http.obj("$HOST/api/v5/public/instruments?instType=SWAP"))

    override suspend fun rates(symbols: List<String>): Map<String, Snapshot> = coroutineScope {
        if (symbols.isEmpty()) return@coroutineScope emptyMap<String, Snapshot>()
        val gate = Semaphore(MAX_PARALLEL)
        val results = symbols.map { instId ->
            async {
                try {
                    gate.withPermit {
                        val encoded = URLEncoder.encode(instId, "UTF-8")
                        parseOne(instId, Http.obj("$HOST/api/v5/public/funding-rate?instId=$encoded"))
                    }
                } catch (_: Exception) {
                    // One bad instrument must not blank out the whole screen.
                    null
                }
            }
        }.awaitAll()

        val out = HashMap<String, Snapshot>()
        for (r in results) if (r != null) out[r.first] = r.second
        out
    }

    internal fun parseInstruments(json: JSONObject): Instruments {
        if (json.str("code") != "0") throw HttpError(json.str("msg").ifEmpty { "OKX error" })
        val data = json.optJSONArray("data") ?: throw HttpError("unexpected OKX payload")
        val out = HashMap<String, MutableMap<Quote, Contract>>()
        for (i in 0 until data.length()) {
            val c = data.optJSONObject(i) ?: continue
            if (c.str("state") != "live") continue
            val instId = c.str("instId")                 // e.g. BTC-USDT-SWAP
            val parts = instId.split("-")
            if (parts.size != 3 || parts[2] != "SWAP") continue
            val quote = Quote.from(parts[1]) ?: continue
            out.addContract(parts[0].uppercase(), quote, Contract(instId, null))
        }
        return out
    }

    internal fun parseOne(instId: String, json: JSONObject): Pair<String, Snapshot>? {
        if (json.str("code") != "0") return null
        val data = json.optJSONArray("data") ?: return null
        val d = data.optJSONObject(0) ?: return null
        val next = d.lng("fundingTime")
        val prev = d.lng("prevFundingTime")
        // OKX does not publish the interval directly, but the distance between
        // the previous and the upcoming settlement is exactly that interval.
        val interval = if (next != null && prev != null && next > prev) {
            (next - prev).toDouble() / 3_600_000.0
        } else {
            null
        }
        return instId to Snapshot(
            rate = d.dbl("fundingRate"),
            nextFundingMs = next,
            serverMs = d.lng("ts"),
            intervalHours = interval
        )
    }
}

/* ------------------------------------------------------------------ *
 * Bybit v5 - api.bybit.com
 * "linear" covers both USDT and USDC perpetuals. Note that the USDC
 * perpetual for BTC is named BTCPERP, which is exactly why symbols are
 * read from the instruments feed instead of being constructed.
 * ------------------------------------------------------------------ */
object BybitClient : ExchangeClient {

    override val exchange = Exchange.BYBIT
    private const val HOST = "https://api.bybit.com"
    private const val MAX_PAGES = 20

    override suspend fun instruments(): Instruments {
        val out = HashMap<String, MutableMap<Quote, Contract>>()
        var cursor = ""
        var page = 0
        while (page < MAX_PAGES) {
            val suffix = if (cursor.isEmpty()) "" else "&cursor=" + URLEncoder.encode(cursor, "UTF-8")
            val json = Http.obj("$HOST/v5/market/instruments-info?category=linear&limit=1000$suffix")
            cursor = mergeInstrumentPage(json, out)
            if (cursor.isEmpty()) break
            page++
        }
        return out
    }

    override suspend fun rates(symbols: List<String>): Map<String, Snapshot> =
        parseRates(Http.obj("$HOST/v5/market/tickers?category=linear"))

    /** Adds one page of instruments and returns the next cursor ("" when done). */
    internal fun mergeInstrumentPage(
        json: JSONObject,
        out: MutableMap<String, MutableMap<Quote, Contract>>
    ): String {
        if (json.optInt("retCode", -1) != 0) {
            throw HttpError(json.str("retMsg").ifEmpty { "Bybit error" })
        }
        val result = json.optJSONObject("result") ?: return ""
        val list = result.optJSONArray("list") ?: return ""
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            if (c.str("contractType") != "LinearPerpetual") continue
            if (c.str("status") != "Trading") continue
            val quote = Quote.from(c.str("quoteCoin")) ?: continue
            val minutes = c.dbl("fundingInterval")
            out.addContract(
                c.str("baseCoin").uppercase(),
                quote,
                // symbol verbatim: BTC's USDC perpetual is "BTCPERP"
                Contract(c.str("symbol"), if (minutes != null && minutes > 0) minutes / 60.0 else null)
            )
        }
        return result.str("nextPageCursor")
    }

    internal fun parseRates(json: JSONObject): Map<String, Snapshot> {
        if (json.optInt("retCode", -1) != 0) {
            throw HttpError(json.str("retMsg").ifEmpty { "Bybit error" })
        }
        val serverMs = json.lng("time")
        val result = json.optJSONObject("result") ?: throw HttpError("unexpected Bybit payload")
        val list = result.optJSONArray("list") ?: throw HttpError("unexpected Bybit payload")
        val out = HashMap<String, Snapshot>(list.length())
        for (i in 0 until list.length()) {
            val d = list.optJSONObject(i) ?: continue
            val symbol = d.str("symbol")
            if (symbol.isEmpty()) continue
            out[symbol] = Snapshot(
                rate = d.dbl("fundingRate"),
                nextFundingMs = d.lng("nextFundingTime"),
                serverMs = serverMs,
                intervalHours = null
            )
        }
        return out
    }
}

object Clients {
    private val map: Map<Exchange, ExchangeClient> = mapOf(
        Exchange.MEXC to MexcClient,
        Exchange.BINANCE to BinanceClient,
        Exchange.OKX to OkxClient,
        Exchange.BYBIT to BybitClient
    )

    operator fun get(exchange: Exchange): ExchangeClient = map.getValue(exchange)
}

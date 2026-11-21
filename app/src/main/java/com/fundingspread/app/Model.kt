package com.fundingspread.app

import java.util.Locale
import kotlin.math.abs

/** The three quote/settlement currencies this app compares. */
enum class Quote(val code: String) {
    USDT("USDT"),
    USDC("USDC"),
    USD1("USD1");

    companion object {
        val ALL: List<Quote> = listOf(USDT, USDC, USD1)
        fun from(code: String?): Quote? {
            if (code.isNullOrEmpty()) return null
            return ALL.firstOrNull { it.code.equals(code, ignoreCase = true) }
        }
    }
}

enum class Exchange(val label: String, val key: String) {
    MEXC("MEXC", "mexc"),
    BINANCE("Binance", "binance"),
    OKX("OKX", "okx"),
    BYBIT("Bybit", "bybit");

    companion object {
        val ALL: List<Exchange> = listOf(MEXC, BINANCE, OKX, BYBIT)
        fun fromKey(key: String?): Exchange = ALL.firstOrNull { it.key == key } ?: MEXC
    }
}

/**
 * One perpetual contract as the exchange itself describes it.
 *
 * [symbol] is always taken verbatim from the exchange's instruments endpoint and
 * never assembled from strings: Bybit's USDC perpetual for BTC is called
 * "BTCPERP", so building "BTCUSDC" would silently request the wrong market.
 */
data class Contract(
    val symbol: String,
    val intervalHours: Double?
)

/** base coin -> quote -> contract. */
typealias Instruments = Map<String, Map<Quote, Contract>>

/** A funding reading for one contract at one moment. */
data class Snapshot(
    val rate: Double?,
    val nextFundingMs: Long?,
    val serverMs: Long?,
    val intervalHours: Double?
)

/** One quote variant of a watched coin, ready for display. */
data class Leg(
    val quote: Quote,
    val symbol: String,
    val rate: Double?,
    val nextFundingMs: Long?,
    val intervalHours: Double?
)

data class SpreadPair(
    val a: Leg,
    val b: Leg,
    val diff: Double
)

data class CoinRow(
    val base: String,
    val listed: Boolean,
    val legs: List<Leg>,
    val best: SpreadPair?,
    val intervalMismatch: Boolean
)

object Spread {

    /**
     * Largest absolute funding-rate gap between any two legs.
     *
     * With three quotes present all three pairs are compared
     * (USDT/USDC, USDT/USD1, USDC/USD1) and the widest one wins.
     * Returns null when fewer than two legs carry a rate, because a gap
     * between one contract and nothing is not a number worth showing.
     */
    fun best(legs: List<Leg>): SpreadPair? {
        val live = legs.filter { it.rate != null }
        if (live.size < 2) return null
        var winner: SpreadPair? = null
        for (i in live.indices) {
            for (j in i + 1 until live.size) {
                val d = abs(live[i].rate!! - live[j].rate!!)
                val current = winner
                if (current == null || d > current.diff) {
                    winner = SpreadPair(live[i], live[j], d)
                }
            }
        }
        return winner
    }

    /**
     * True when the legs settle on different schedules. A 0.01% gap between a
     * 4h contract and an 8h contract is not the same trade as the same gap
     * between two 8h contracts, so the UI flags it rather than hiding it.
     */
    fun intervalMismatch(legs: List<Leg>): Boolean {
        val ivs = legs.mapNotNull { it.intervalHours }
        if (ivs.size < 2) return false
        val first = ivs[0]
        return ivs.any { abs(it - first) > 0.01 }
    }

    fun buildRows(
        bases: List<String>,
        instruments: Instruments,
        rates: Map<String, Snapshot>
    ): List<CoinRow> = bases.map { base ->
        val byQuote = instruments[base]
        val legs = ArrayList<Leg>(3)
        if (byQuote != null) {
            for (q in Quote.ALL) {
                val c = byQuote[q] ?: continue
                val snap = rates[c.symbol]
                legs.add(
                    Leg(
                        quote = q,
                        symbol = c.symbol,
                        rate = snap?.rate,
                        nextFundingMs = snap?.nextFundingMs,
                        intervalHours = snap?.intervalHours ?: c.intervalHours
                    )
                )
            }
        }
        CoinRow(
            base = base,
            listed = byQuote != null,
            legs = legs,
            best = best(legs),
            intervalMismatch = intervalMismatch(legs)
        )
    }
}

object Fmt {

    /** Funding rates are decimals: 0.0005 -> "+0.0500%". */
    fun pct(rate: Double?): String {
        if (rate == null) return "—"
        val p = rate * 100.0
        val sign = if (p > 0) "+" else if (p < 0) "−" else ""
        return sign + String.format(Locale.US, "%.4f", abs(p)) + "%"
    }

    fun gap(diff: Double?): String {
        if (diff == null) return "—"
        return String.format(Locale.US, "%.4f", diff * 100.0) + "%"
    }

    fun countdown(msLeft: Long?): String {
        if (msLeft == null) return "—"
        val ms = if (msLeft < 0) 0L else msLeft
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun interval(h: Double?): String {
        if (h == null || h <= 0.0) return "?"
        val rounded = Math.round(h * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) "${rounded.toInt()}h" else "${rounded}h"
    }

    /**
     * Users type a base coin, but "BTCUSDT" pasted from an exchange should work
     * too, so a trailing quote code is stripped.
     */
    fun normaliseBase(input: String): String {
        var s = input.trim().uppercase().filter { it.isLetterOrDigit() }
        for (q in Quote.ALL) {
            if (s.length > q.code.length && s.endsWith(q.code)) {
                s = s.substring(0, s.length - q.code.length)
                break
            }
        }
        return s
    }
}

package com.fundingspread.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class UiState(
    val exchange: Exchange = Exchange.MEXC,
    val counts: Map<Exchange, Int> = emptyMap(),
    val rows: List<CoinRow> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val lastUpdatedMs: Long? = null,
    /** Wall clock corrected to the selected exchange's server time. */
    val exchangeNowMs: Long = System.currentTimeMillis(),
    val secondsToRefresh: Int = MainViewModel.REFRESH_SECONDS,
    /** Set when the exchange lists only one of USDT/USDC/USD1 — no gap is possible. */
    val onlyQuote: Quote? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val REFRESH_SECONDS = 10
        private const val PREFS = "funding_spread"
        private const val KEY_LISTS = "lists"
        private const val KEY_EXCHANGE = "exchange"
        private const val INSTRUMENT_TTL_MS = 30 * 60 * 1000L
    }

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val watchlists: MutableMap<Exchange, MutableList<String>> = HashMap()
    private val instruments: MutableMap<Exchange, Instruments> = HashMap()
    private val instrumentsFetchedAt: MutableMap<Exchange, Long> = HashMap()
    private val rates: MutableMap<Exchange, Map<String, Snapshot>> = HashMap()
    private val clockOffset: MutableMap<Exchange, Long> = HashMap()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var secondsLeft = REFRESH_SECONDS

    init {
        loadPrefs()
        _state.value = _state.value.copy(
            exchange = Exchange.fromKey(prefs.getString(KEY_EXCHANGE, Exchange.MEXC.key)),
            counts = countsMap()
        )
        rebuild()
        refresh(forceInstruments = true)
        startClock()
    }

    /* ----------------------------- persistence ----------------------------- */

    private fun loadPrefs() {
        for (ex in Exchange.ALL) watchlists[ex] = ArrayList()
        val raw = prefs.getString(KEY_LISTS, null) ?: return
        try {
            val obj = JSONObject(raw)
            for (ex in Exchange.ALL) {
                val arr = obj.optJSONArray(ex.key) ?: continue
                val list = watchlists.getValue(ex)
                for (i in 0 until arr.length()) {
                    val base = arr.optString(i, "").uppercase()
                    if (base.isNotEmpty() && !list.contains(base)) list.add(base)
                }
            }
        } catch (_: Exception) {
            // corrupt preference: start from an empty watchlist rather than crash
        }
    }

    private fun savePrefs() {
        try {
            val obj = JSONObject()
            for (ex in Exchange.ALL) {
                obj.put(ex.key, JSONArray(watchlists.getValue(ex)))
            }
            prefs.edit()
                .putString(KEY_LISTS, obj.toString())
                .putString(KEY_EXCHANGE, _state.value.exchange.key)
                .apply()
        } catch (_: Exception) {
            // non-fatal
        }
    }

    private fun countsMap(): Map<Exchange, Int> =
        Exchange.ALL.associateWith { watchlists[it]?.size ?: 0 }

    /* ------------------------------- actions ------------------------------- */

    fun selectExchange(exchange: Exchange) {
        if (_state.value.exchange == exchange) return
        _state.value = _state.value.copy(exchange = exchange, error = null)
        savePrefs()
        rebuild()
        refresh(forceInstruments = false)
    }

    fun addBase(input: String) {
        val base = Fmt.normaliseBase(input)
        if (base.isEmpty()) return
        val ex = _state.value.exchange
        val list = watchlists.getValue(ex)
        if (list.contains(base)) return
        list.add(0, base)
        savePrefs()
        _state.value = _state.value.copy(counts = countsMap())
        rebuild()
        // A coin the cached instrument list has never seen may have just been
        // listed, so re-read the instrument feed before giving up on it.
        val unknown = instruments[ex]?.containsKey(base) != true
        refresh(forceInstruments = unknown)
    }

    fun removeBase(base: String) {
        val ex = _state.value.exchange
        watchlists.getValue(ex).remove(base)
        savePrefs()
        _state.value = _state.value.copy(counts = countsMap())
        rebuild()
    }

    fun refreshNow() = refresh(forceInstruments = false)

    /* ------------------------------ refreshing ----------------------------- */

    private fun refresh(forceInstruments: Boolean) {
        if (refreshJob?.isActive == true) return
        val ex = _state.value.exchange
        _state.value = _state.value.copy(loading = true)
        refreshJob = viewModelScope.launch {
            try {
                val client = Clients[ex]

                val stale = (System.currentTimeMillis() - (instrumentsFetchedAt[ex] ?: 0L)) > INSTRUMENT_TTL_MS
                if (forceInstruments || instruments[ex] == null || stale) {
                    instruments[ex] = client.instruments()
                    instrumentsFetchedAt[ex] = System.currentTimeMillis()
                }

                val symbols = symbolsToWatch(ex)
                if (symbols.isNotEmpty()) {
                    val fresh = client.rates(symbols)
                    rates[ex] = fresh
                    fresh.values.firstOrNull { it.serverMs != null }?.serverMs?.let {
                        clockOffset[ex] = it - System.currentTimeMillis()
                    }
                }

                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    lastUpdatedMs = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "network unavailable"
                )
            }
            secondsLeft = REFRESH_SECONDS
            rebuild()
        }
    }

    private fun symbolsToWatch(ex: Exchange): List<String> {
        val inst = instruments[ex] ?: return emptyList()
        val out = ArrayList<String>()
        for (base in watchlists.getValue(ex)) {
            val byQuote = inst[base] ?: continue
            for (q in Quote.ALL) byQuote[q]?.let { out.add(it.symbol) }
        }
        return out
    }

    /* -------------------------------- state -------------------------------- */

    private fun rebuild() {
        val ex = _state.value.exchange
        val inst = instruments[ex] ?: emptyMap()
        val rows = Spread.buildRows(watchlists.getValue(ex), inst, rates[ex] ?: emptyMap())

        // If the whole exchange only lists a single quote currency, no coin on
        // it can ever produce a gap. Say so instead of showing empty dashes.
        var only: Quote? = null
        if (inst.isNotEmpty()) {
            val seen = HashSet<Quote>()
            for (byQuote in inst.values) seen.addAll(byQuote.keys)
            if (seen.size == 1) only = seen.first()
        }

        _state.value = _state.value.copy(
            rows = rows,
            counts = countsMap(),
            onlyQuote = only,
            exchangeNowMs = System.currentTimeMillis() + (clockOffset[ex] ?: 0L)
        )
    }

    /**
     * One-second tick drives the countdowns; every tenth tick triggers a data
     * refresh. Countdowns are anchored to the exchange clock, so a phone whose
     * own clock is off still shows the right time to settlement.
     */
    private fun startClock() {
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val ex = _state.value.exchange
                secondsLeft -= 1
                if (secondsLeft <= 0) {
                    secondsLeft = REFRESH_SECONDS
                    refresh(forceInstruments = false)
                }
                _state.value = _state.value.copy(
                    exchangeNowMs = System.currentTimeMillis() + (clockOffset[ex] ?: 0L),
                    secondsToRefresh = secondsLeft
                )
            }
        }
    }
}

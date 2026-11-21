package com.fundingspread.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Off-device checks for the parsing and spread layer.
 * Run with:  ./gradlew :app:testDebugUnitTest
 *
 * The payload shapes here are the ones the exchanges actually return; the OKX
 * block is a verbatim capture of a live /api/v5/public/funding-rate response.
 */
class FundingSpreadTest {

    private val h = 3_600_000L
    private val srv = 1_787_317_523_295L
    private val next1h = srv + h
    private val next4h = srv + 4 * h

    private val mexcInstruments = MexcClient.parseInstruments(
        JSONObject(
            """
            {"success":true,"code":0,"data":[
              {"symbol":"BTC_USDT","baseCoin":"BTC","quoteCoin":"USDT","futureType":1,"state":0},
              {"symbol":"BTC_USDC","baseCoin":"BTC","quoteCoin":"USDC","futureType":1,"state":0},
              {"symbol":"BTC_USD1","baseCoin":"BTC","quoteCoin":"USD1","futureType":1,"state":0},
              {"symbol":"ETH_USDT","baseCoin":"ETH","quoteCoin":"USDT","futureType":1,"state":0},
              {"symbol":"OLD_USDT","baseCoin":"OLD","quoteCoin":"USDT","futureType":1,"state":3},
              {"symbol":"XRP_USDT","baseCoin":"XRP","quoteCoin":"USDT","futureType":2,"state":0}
            ]}
            """.trimIndent()
        )
    )

    private val mexcRates = MexcClient.parseRates(
        JSONObject(
            """
            {"success":true,"code":0,"data":[
              {"symbol":"BTC_USDT","fundingRate":0.0005,"collectCycle":8,"nextSettleTime":$next1h,"timestamp":$srv},
              {"symbol":"BTC_USDC","fundingRate":-0.00001,"collectCycle":8,"nextSettleTime":$next1h,"timestamp":$srv},
              {"symbol":"BTC_USD1","fundingRate":0.0002,"collectCycle":8,"nextSettleTime":$next1h,"timestamp":$srv},
              {"symbol":"ETH_USDT","fundingRate":0.0001,"collectCycle":8,"nextSettleTime":$next1h,"timestamp":$srv}
            ]}
            """.trimIndent()
        )
    )

    @Test
    fun `mexc resolves all three quotes and drops dead contracts`() {
        val btc = mexcInstruments["BTC"]!!
        assertEquals(3, btc.size)
        assertEquals("BTC_USDT", btc[Quote.USDT]?.symbol)
        assertEquals("BTC_USDC", btc[Quote.USDC]?.symbol)
        assertEquals("BTC_USD1", btc[Quote.USD1]?.symbol)
        assertFalse("delisted state 3 must be dropped", mexcInstruments.containsKey("OLD"))
        assertFalse("delivery future must be dropped", mexcInstruments.containsKey("XRP"))
    }

    @Test
    fun `mexc rates keep sign, schedule and server clock`() {
        assertEquals(0.0005, mexcRates["BTC_USDT"]!!.rate!!, 1e-12)
        assertEquals(-0.00001, mexcRates["BTC_USDC"]!!.rate!!, 1e-12)
        assertEquals(8.0, mexcRates["BTC_USDT"]!!.intervalHours!!, 1e-9)
        assertEquals(srv, mexcRates["BTC_USDT"]!!.serverMs!!)
        assertEquals(next1h, mexcRates["BTC_USDT"]!!.nextFundingMs!!)
    }

    @Test
    fun `widest of the three pairs wins`() {
        // USDT/USDC = 0.0510%   USDT/USD1 = 0.0300%   USDC/USD1 = 0.0210%
        val row = Spread.buildRows(listOf("BTC"), mexcInstruments, mexcRates).first()
        assertEquals(3, row.legs.size)
        assertEquals("0.0510%", Fmt.gap(row.best!!.diff))
        assertEquals(Quote.USDT, row.best!!.a.quote)
        assertEquals(Quote.USDC, row.best!!.b.quote)
        assertFalse(row.intervalMismatch)
    }

    @Test
    fun `widest pair wins even when it is compared last`() {
        val row = Spread.buildRows(
            listOf("BTC"), mexcInstruments,
            mapOf(
                "BTC_USDT" to Snapshot(0.0001, next1h, srv, 8.0),
                "BTC_USDC" to Snapshot(0.0002, next1h, srv, 8.0),
                "BTC_USD1" to Snapshot(-0.0009, next1h, srv, 8.0)
            )
        ).first()
        assertEquals(Quote.USDC, row.best!!.a.quote)
        assertEquals(Quote.USD1, row.best!!.b.quote)
        assertEquals("0.1100%", Fmt.gap(row.best!!.diff))
    }

    @Test
    fun `a gap is never invented from missing or single quotes`() {
        val single = Spread.buildRows(listOf("ETH"), mexcInstruments, mexcRates).first()
        assertNull("one quote cannot produce a gap", single.best)
        assertTrue(single.listed)

        val unlisted = Spread.buildRows(listOf("PEPE"), mexcInstruments, mexcRates).first()
        assertFalse(unlisted.listed)
        assertNull(unlisted.best)

        val partial = Spread.buildRows(
            listOf("BTC"), mexcInstruments,
            mexcRates.filterKeys { it == "BTC_USDT" }
        ).first()
        assertNull("legs without a rate are not paired", partial.best)
        assertEquals("legs are still listed for context", 3, partial.legs.size)
        assertEquals("—", Fmt.pct(partial.legs[1].rate))
    }

    @Test
    fun `bybit usdc perpetual is BTCPERP and pagination terminates`() {
        val out = HashMap<String, MutableMap<Quote, Contract>>()
        val cursor = BybitClient.mergeInstrumentPage(
            JSONObject(
                """
                {"retCode":0,"result":{"nextPageCursor":"PAGE2","list":[
                  {"symbol":"BTCUSDT","baseCoin":"BTC","quoteCoin":"USDT","contractType":"LinearPerpetual","status":"Trading","fundingInterval":480}
                ]}}
                """.trimIndent()
            ),
            out
        )
        assertEquals("PAGE2", cursor)

        val done = BybitClient.mergeInstrumentPage(
            JSONObject(
                """
                {"retCode":0,"result":{"nextPageCursor":"","list":[
                  {"symbol":"BTCPERP","baseCoin":"BTC","quoteCoin":"USDC","contractType":"LinearPerpetual","status":"Trading","fundingInterval":240},
                  {"symbol":"BTC-26SEP25","baseCoin":"BTC","quoteCoin":"USDC","contractType":"LinearFutures","status":"Trading","fundingInterval":480},
                  {"symbol":"SOONUSDT","baseCoin":"SOON","quoteCoin":"USDT","contractType":"LinearPerpetual","status":"PreLaunch","fundingInterval":480}
                ]}}
                """.trimIndent()
            ),
            out
        )
        assertEquals("", done)
        assertEquals("BTCUSDT", out["BTC"]?.get(Quote.USDT)?.symbol)
        assertEquals("string-built BTCUSDC would be the wrong market", "BTCPERP", out["BTC"]?.get(Quote.USDC)?.symbol)
        assertEquals(8.0, out["BTC"]!![Quote.USDT]!!.intervalHours!!, 1e-9)
        assertEquals(4.0, out["BTC"]!![Quote.USDC]!!.intervalHours!!, 1e-9)
        assertEquals("dated future excluded", 2, out["BTC"]!!.size)
        assertFalse("PreLaunch excluded", out.containsKey("SOON"))

        val rates = BybitClient.parseRates(
            JSONObject(
                """
                {"retCode":0,"time":$srv,"result":{"list":[
                  {"symbol":"BTCUSDT","fundingRate":"0.00012500","nextFundingTime":"$next1h"},
                  {"symbol":"BTCPERP","fundingRate":"-0.00007500","nextFundingTime":"$next4h"}
                ]}}
                """.trimIndent()
            )
        )
        assertEquals(0.000125, rates["BTCUSDT"]!!.rate!!, 1e-12)
        assertEquals(srv, rates["BTCPERP"]!!.serverMs!!)

        val row = Spread.buildRows(listOf("BTC"), out, rates).first()
        assertEquals("0.0200%", Fmt.gap(row.best!!.diff))
        assertTrue("8h against 4h must be flagged", row.intervalMismatch)
    }

    @Test(expected = HttpError::class)
    fun `bybit error code raises rather than returning nothing`() {
        BybitClient.parseRates(JSONObject("""{"retCode":10001,"retMsg":"params error","result":{}}"""))
    }

    @Test
    fun `binance applies per-symbol funding intervals and filters non-perpetuals`() {
        val custom = BinanceClient.parseFundingInfo(
            JSONArray("""[{"symbol":"BTCUSDC","fundingIntervalHours":4}]""")
        )
        val inst = BinanceClient.parseInstruments(
            JSONObject(
                """
                {"symbols":[
                  {"symbol":"BTCUSDT","baseAsset":"BTC","quoteAsset":"USDT","contractType":"PERPETUAL","status":"TRADING"},
                  {"symbol":"BTCUSDC","baseAsset":"BTC","quoteAsset":"USDC","contractType":"PERPETUAL","status":"TRADING"},
                  {"symbol":"1000PEPEUSDT","baseAsset":"1000PEPE","quoteAsset":"USDT","contractType":"PERPETUAL","status":"TRADING"},
                  {"symbol":"BTCUSDT_250926","baseAsset":"BTC","quoteAsset":"USDT","contractType":"CURRENT_QUARTER","status":"TRADING"},
                  {"symbol":"DEADUSDT","baseAsset":"DEAD","quoteAsset":"USDT","contractType":"PERPETUAL","status":"SETTLING"}
                ]}
                """.trimIndent()
            ),
            custom
        )
        assertEquals(8.0, inst["BTC"]!![Quote.USDT]!!.intervalHours!!, 1e-9)
        assertEquals(4.0, inst["BTC"]!![Quote.USDC]!!.intervalHours!!, 1e-9)
        assertEquals("1000PEPEUSDT", inst["1000PEPE"]?.get(Quote.USDT)?.symbol)
        assertEquals("quarterly excluded", 2, inst["BTC"]!!.size)
        assertFalse("SETTLING excluded", inst.containsKey("DEAD"))

        val rates = BinanceClient.parseRates(
            JSONArray(
                """
                [{"symbol":"BTCUSDT","lastFundingRate":"0.00010000","nextFundingTime":$next1h,"time":$srv},
                 {"symbol":"BTCUSDC","lastFundingRate":"-0.00030000","nextFundingTime":$next4h,"time":$srv}]
                """.trimIndent()
            )
        )
        val row = Spread.buildRows(listOf("BTC"), inst, rates).first()
        assertEquals("0.0400%", Fmt.gap(row.best!!.diff))
    }

    @Test
    fun `okx live payload parses and the interval is derived from its own timestamps`() {
        val live = JSONObject(
            """
            {"code":"0","data":[{"fundingRate":"0.0001000000000000","fundingTime":"1787328000000",
            "instId":"BTC-USDT-SWAP","nextFundingRate":"","nextFundingTime":"1787356800000",
            "prevFundingTime":"1787299200000","settState":"settled","ts":"1787311226178"}],"msg":""}
            """.trimIndent()
        )
        val one = OkxClient.parseOne("BTC-USDT-SWAP", live)!!
        assertEquals(0.0001, one.second.rate!!, 1e-12)
        assertEquals("countdown targets fundingTime", 1_787_328_000_000L, one.second.nextFundingMs!!)
        assertEquals(1_787_311_226_178L, one.second.serverMs!!)
        assertEquals(8.0, one.second.intervalHours!!, 1e-9)
    }

    @Test
    fun `okx ignores inverse and suspended swaps`() {
        val inst = OkxClient.parseInstruments(
            JSONObject(
                """
                {"code":"0","msg":"","data":[
                  {"instId":"BTC-USDT-SWAP","state":"live"},
                  {"instId":"BTC-USD-SWAP","state":"live"},
                  {"instId":"OLD-USDT-SWAP","state":"suspend"},
                  {"instId":"BTC-USDT-250926","state":"live"}
                ]}
                """.trimIndent()
            )
        )
        assertEquals(1, inst["BTC"]!!.size)
        assertEquals("BTC-USDT-SWAP", inst["BTC"]?.get(Quote.USDT)?.symbol)
        assertFalse(inst.containsKey("OLD"))
    }

    @Test
    fun `formatting is stable and locale independent`() {
        assertEquals("+0.0500%", Fmt.pct(0.0005))
        assertEquals("−0.0010%", Fmt.pct(-0.00001))
        assertEquals("—", Fmt.pct(null))
        assertEquals("0.0510%", Fmt.gap(0.00051))
        assertEquals("47:59", Fmt.countdown(47 * 60_000L + 59_000L))
        assertEquals("3:11:58", Fmt.countdown(3 * h + 11 * 60_000L + 58_000L))
        assertEquals("00:00", Fmt.countdown(-5_000L))
        assertEquals("8h", Fmt.interval(8.0))
        assertEquals("0.5h", Fmt.interval(0.5))
        assertEquals("?", Fmt.interval(null))

        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("a comma here would be wrong", "+0.0500%", Fmt.pct(0.0005))
            assertEquals("LINK", Fmt.normaliseBase("link"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `input is normalised to a base coin`() {
        assertEquals("BTC", Fmt.normaliseBase("btc"))
        assertEquals("BTC", Fmt.normaliseBase("BTCUSDT"))
        assertEquals("SOL", Fmt.normaliseBase("solusd1"))
        assertEquals("BTC", Fmt.normaliseBase(" btc-usdt "))
        assertEquals("1000PEPE", Fmt.normaliseBase("1000PEPEUSDT"))
        assertEquals("a bare quote name stays intact", "USDT", Fmt.normaliseBase("USDT"))
    }
}

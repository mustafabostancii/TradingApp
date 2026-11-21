package com.fundingspread.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private object C {
    val bg = Color(0xFF0B0F14)
    val bg2 = Color(0xFF121820)
    val card = Color(0xFF151D27)
    val card2 = Color(0xFF1B2431)
    val line = Color(0xFF243040)
    val line2 = Color(0xFF2F3D50)
    val tx = Color(0xFFE8EEF7)
    val tx2 = Color(0xFF93A3B8)
    val tx3 = Color(0xFF64748B)
    val pos = Color(0xFF34D399)
    val neg = Color(0xFFF87171)
    val acc = Color(0xFF38BDF8)
    val warn = Color(0xFFFBBF24)
}

private val Mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppScreen() }
    }
}

@Composable
fun AppScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current

    fun submit() {
        vm.addBase(input)
        input = ""
        focus.clearFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(C.bg)
    ) {
        Header(state)
        ExchangeTabs(state) { vm.selectExchange(it) }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                AddBar(
                    value = input,
                    onValue = { input = it },
                    onSubmit = { submit() }
                )
            }
            item { Hint() }

            state.error?.let { message ->
                item { Notice(border = C.neg, title = "${state.exchange.label} unreachable", body = "$message — values below may be stale.") }
            }
            state.onlyQuote?.let { q ->
                if (state.rows.isNotEmpty()) {
                    val missing = Quote.ALL.filter { it != q }.joinToString(" / ") { it.code }
                    item {
                        Notice(
                            border = C.warn,
                            title = "${state.exchange.label} lists ${q.code} perpetuals only",
                            body = "No $missing markets are available here, and a gap needs two quotes on the same coin. " +
                                "The app re-checks automatically if that changes."
                        )
                    }
                }
            }

            if (state.rows.isEmpty()) {
                item { EmptyState(state.exchange) }
            } else {
                items(state.rows, key = { it.base }) { row ->
                    CoinCard(row, state.exchangeNowMs) { vm.removeBase(row.base) }
                }
            }

            item { Footer(state) { vm.refreshNow() } }
        }
    }
}

@Composable
private fun Header(state: UiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(C.bg)
            .padding(horizontal = 14.dp)
            .padding(top = 14.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Funding", color = C.tx, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Spread", color = C.acc, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(7.dp)
                    .background(
                        when {
                            state.loading -> C.acc
                            state.error != null -> C.neg
                            else -> C.pos
                        },
                        CircleShape
                    )
            )
            Spacer(Modifier.width(7.dp))
            Text(
                when {
                    state.loading -> "updating…"
                    state.error != null -> "error"
                    state.lastUpdatedMs != null -> clockLabel(state.lastUpdatedMs)
                    else -> "starting…"
                },
                color = C.tx2, fontSize = 11.sp, fontFamily = Mono
            )
        }
    }
}

private fun clockLabel(ms: Long): String {
    val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    return t.format(java.util.Date(ms))
}

@Composable
private fun ExchangeTabs(state: UiState, onSelect: (Exchange) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(C.bg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (ex in Exchange.ALL) {
            val on = ex == state.exchange
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) C.acc else C.bg2)
                    .border(1.dp, if (on) C.acc else C.line, RoundedCornerShape(10.dp))
                    .clickable { onSelect(ex) }
                    .widthIn(min = 82.dp)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ex.label,
                    color = if (on) Color(0xFF06121C) else C.tx2,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                val n = state.counts[ex] ?: 0
                if (n > 0) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        n.toString(),
                        color = if (on) Color(0xFF06121C).copy(alpha = 0.7f) else C.tx3,
                        fontSize = 10.sp,
                        fontFamily = Mono
                    )
                }
            }
        }
    }
}

@Composable
private fun AddBar(value: String, onValue: (String) -> Unit, onSubmit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(11.dp))
                .background(C.card)
                .border(1.dp, C.line, RoundedCornerShape(11.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            if (value.isEmpty()) {
                Text("Add a coin — type BTC, ETH, SOL…", color = C.tx3, fontSize = 15.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = { onValue(it.uppercase()) },
                singleLine = true,
                textStyle = TextStyle(
                    color = C.tx,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                cursorBrush = SolidColor(C.acc),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(if (value.isBlank()) C.acc.copy(alpha = 0.45f) else C.acc)
                .clickable(enabled = value.isNotBlank()) { onSubmit() }
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text("Add", color = Color(0xFF06121C), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Hint() {
    Text(
        "Type the base coin only. The app finds every USDT / USDC / USD1 perpetual for it on the " +
            "selected exchange, then shows the largest absolute funding-rate gap between them.",
        color = C.tx3,
        fontSize = 11.5.sp,
        lineHeight = 17.sp
    )
}

@Composable
private fun Notice(border: Color, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(C.card)
            .border(1.dp, C.line, RoundedCornerShape(10.dp))
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(border)
        )
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, color = C.tx, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(body, color = C.tx2, fontSize = 11.5.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun EmptyState(ex: Exchange) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("◎", color = C.tx3.copy(alpha = 0.5f), fontSize = 34.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "No coins on your ${ex.label} list yet.\nType a base coin above — BTC, not BTCUSDT.",
            color = C.tx3,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CoinCard(row: CoinRow, nowMs: Long, onRemove: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.card)
            .border(1.dp, C.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(row.base, color = C.tx, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitleFor(row),
                    color = if (row.intervalMismatch) C.warn else C.tx3,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (row.best != null) Fmt.gap(row.best.diff) else "—",
                    color = if (row.best != null) C.tx else C.tx3,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Mono
                )
                Spacer(Modifier.height(3.dp))
                Text("MAX GAP", color = C.tx3, fontSize = 9.5.sp, letterSpacing = 0.8.sp)
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, C.line2, RoundedCornerShape(8.dp))
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = C.tx3, fontSize = 15.sp)
            }
        }

        if (row.legs.isNotEmpty()) {
            Spacer(Modifier.height(11.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(C.line)
            )
            for (leg in row.legs) {
                LegRow(leg, row, nowMs)
            }
        }
    }
}

private fun subtitleFor(row: CoinRow): String = when {
    !row.listed -> "not listed on this exchange"
    row.legs.isEmpty() -> "no USDT / USDC / USD1 perp"
    row.best == null -> "${row.legs.size} quote — need 2+ to compare"
    row.intervalMismatch -> "${row.best.a.quote.code} ↔ ${row.best.b.quote.code}  ⚠ different intervals"
    else -> "${row.best.a.quote.code} ↔ ${row.best.b.quote.code}"
}

@Composable
private fun LegRow(leg: Leg, row: CoinRow, nowMs: Long) {
    val isBest = row.best != null && (row.best.a === leg || row.best.b === leg)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .widthIn(min = 46.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isBest) C.acc.copy(alpha = 0.14f) else C.card2)
                .border(
                    1.dp,
                    if (isBest) C.acc.copy(alpha = 0.5f) else C.line2,
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 7.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                leg.quote.code,
                color = if (isBest) C.acc else C.tx2,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            leg.symbol,
            color = C.tx3,
            fontSize = 10.5.sp,
            fontFamily = Mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(if (row.intervalMismatch) C.warn.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(
                Fmt.interval(leg.intervalHours),
                color = if (row.intervalMismatch) C.warn else C.tx3,
                fontSize = 10.sp,
                fontFamily = Mono
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            Fmt.pct(leg.rate),
            color = when {
                leg.rate == null -> C.tx3
                leg.rate > 0 -> C.pos
                leg.rate < 0 -> C.neg
                else -> C.tx3
            },
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = Mono,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 82.dp)
        )
        Spacer(Modifier.width(8.dp))
        val left = leg.nextFundingMs?.minus(nowMs)
        Text(
            Fmt.countdown(left),
            color = if (left != null && left <= 5 * 60 * 1000L) C.warn else C.tx2,
            fontSize = 12.sp,
            fontFamily = Mono,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 62.dp)
        )
    }
}

@Composable
private fun Footer(state: UiState, onRefresh: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Auto-refresh ${state.secondsToRefresh}s · ${state.exchange.label}",
            color = C.tx3,
            fontSize = 11.sp
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, C.line, RoundedCornerShape(8.dp))
                .clickable { onRefresh() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("Refresh now", color = C.tx3, fontSize = 11.sp)
        }
    }
}

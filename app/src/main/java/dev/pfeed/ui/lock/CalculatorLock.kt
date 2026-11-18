package dev.pfeed.ui.lock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pfeed.security.AppLockManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Settings → Privacy: whether the lock screen is the calculator (set from settings at startup). */
object LockLook {
    val calculator = MutableStateFlow(false)
}

/**
 * The lock screen disguised as a calculator, and it is one: it adds, subtracts, multiplies and
 * divides. Type your PIN and press = to get in. (Hold = for fingerprint or face, if set up.)
 * Nothing on it says what's behind it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalculatorLock(lock: AppLockManager, onBiometric: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var calc by remember { mutableStateOf(Calc()) }

    fun press(key: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (key == "=" && calc.isPlainNumber() && calc.entry.length >= 4) {
            // Digits on their own, then =: that's how the PIN goes in. Wrong = it just stays a number.
            val chars = calc.entry.toCharArray()
            scope.launch {
                if (lock.unlockWithPin(chars) == AppLockManager.PinResult.Ok) calc = Calc()
            }
            return
        }
        calc = calc.press(key)
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF101114)).statusBarsPadding().navigationBarsPadding().padding(16.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            calc.expression,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 22.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Text(
            calc.display,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp).testTag("calc_display"),
            color = Color.White,
            fontSize = if (calc.display.length > 9) 44.sp else 64.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        val rows = listOf(
            listOf("C", "±", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "⌫", "="),
        )
        Box(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { k ->
                            val op = k in OPS || k == "="
                            val fn = k in setOf("C", "±", "%")
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).testTag("calc_$k").clip(CircleShape)
                                    .background(
                                        when {
                                            op -> Color(0xFFFF9F0A)
                                            fn -> Color(0xFFA5A5A5)
                                            else -> Color(0xFF2C2C2E)
                                        },
                                    )
                                    .combinedClickable(
                                        onClick = { press(k) },
                                        onLongClick = if (k == "=") ({ onBiometric() }) else null,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(k, color = if (fn) Color.Black else Color.White, fontSize = 30.sp)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.padding(4.dp))
    }
}

private val OPS = setOf("+", "−", "×", "÷")

/** A small calculator: one running total and the number being typed, like a pocket calculator. */
internal data class Calc(
    val entry: String = "",
    val total: BigDecimal? = null,
    val op: String? = null,
    val expression: String = "",
    val justEvaluated: Boolean = false,
) {
    val display: String get() = entry.ifEmpty { total?.let(::fmt) ?: "0" }

    fun isPlainNumber(): Boolean = op == null && total == null && entry.isNotEmpty() && entry.all(Char::isDigit)

    fun press(k: String): Calc = when {
        k == "C" -> Calc()
        k == "⌫" -> copy(entry = entry.dropLast(1))
        k.length == 1 && k[0].isDigit() -> if (justEvaluated) Calc(entry = k) else if (entry.length >= 15) this else copy(entry = if (entry == "0") k else entry + k)
        k == "." -> if ("." in entry) this else copy(entry = (if (justEvaluated) "" else entry).ifEmpty { "0" } + ".", justEvaluated = false)
        k == "±" -> if (entry.isNotEmpty()) copy(entry = if (entry.startsWith("-")) entry.drop(1) else "-$entry") else total?.let { copy(total = it.negate()) } ?: this
        k == "%" -> entry.toBigDecimalOrNull()?.let { copy(entry = fmt(it.divide(BigDecimal(100)))) } ?: this
        k in OPS -> {
            val now = entry.toBigDecimalOrNull()
            val t = if (now == null) total else if (total == null || op == null) now else apply(total, op, now)
            if (t == null) this else Calc(total = t, op = k, expression = fmt(t) + " " + k)
        }
        k == "=" -> {
            val now = entry.toBigDecimalOrNull()
            if (total == null || op == null || now == null) copy(justEvaluated = true)
            else {
                val r = apply(total, op, now)
                Calc(entry = r?.let(::fmt) ?: "Error", expression = "${fmt(total)} $op ${fmt(now)} =", justEvaluated = true)
            }
        }
        else -> this
    }

    private fun apply(a: BigDecimal, op: String, b: BigDecimal): BigDecimal? = when (op) {
        "+" -> a + b
        "−" -> a - b
        "×" -> a.multiply(b, MathContext.DECIMAL64)
        "÷" -> if (b.signum() == 0) null else a.divide(b, MathContext.DECIMAL64)
        else -> null
    }

    private fun fmt(v: BigDecimal): String {
        val r = v.round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        return if (r.scale() < 0) r.setScale(0).toPlainString() else r.toPlainString()
    }
}

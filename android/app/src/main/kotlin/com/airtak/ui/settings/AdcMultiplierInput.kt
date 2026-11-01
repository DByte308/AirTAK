package com.airtak.ui.settings

import com.airtak.config.constants.ADC_MULTIPLIER_DEFAULT
import com.airtak.config.constants.ADC_MULTIPLIER_MAX
import com.airtak.config.constants.ADC_MULTIPLIER_MIN

internal enum class AdcInputError { Empty, InvalidNumber, OutOfRange }

/** Keep the exact edit buffer: empty text, trailing separators and zeros must not be reformatted. */
data class AdcMultiplierInput(val text: String) {
    private val normalized = text.trim().replace(',', '.')
    private val number = normalized.takeIf { DECIMAL.matches(it) }?.toDoubleOrNull()

    internal val error: AdcInputError? = when {
        normalized.isEmpty() -> AdcInputError.Empty
        number == null || !number.isFinite() -> AdcInputError.InvalidNumber
        number < ADC_MULTIPLIER_MIN || number > ADC_MULTIPLIER_MAX -> AdcInputError.OutOfRange
        number != 0.0 && number.toFloat() == 0f -> AdcInputError.InvalidNumber
        else -> null
    }
    val value: Float? = if (error == null) number?.toFloat() else null
    val isValid: Boolean get() = value != null

    companion object {
        private val DECIMAL = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")

        fun fromValue(value: Float): AdcMultiplierInput {
            val safe = value.takeIf {
                it.isFinite() && it >= ADC_MULTIPLIER_MIN && it <= ADC_MULTIPLIER_MAX
            } ?: ADC_MULTIPLIER_DEFAULT
            return AdcMultiplierInput(java.math.BigDecimal(safe.toString()).stripTrailingZeros().toPlainString())
        }
    }
}

package com.airtak.ui.settings

/** Integer text fields currently supported by the settings forms (ADC has its own decimal model). */
enum class SettingsNumericField { BroadcastInterval, MinimumTxInterval, DisplayTimeout, PlayerColor }
internal enum class NumericInputError { Empty, NotWholeNumber, OutOfRange, InvalidColor }

data class SettingsNumericInput(val field: SettingsNumericField, val text: String) {
    private val digits = text.trim().let { if (field == SettingsNumericField.PlayerColor) it.removePrefix("#") else it }
    private val isColor = field == SettingsNumericField.PlayerColor
    private val validFormat = if (isColor) RGB.matches(digits) else WHOLE.matches(digits)
    private val parsed = if (validFormat) digits.toLongOrNull(if (isColor) 16 else 10) else null
    internal val error: NumericInputError? = when {
        text.isBlank() -> NumericInputError.Empty
        !validFormat -> if (isColor) NumericInputError.InvalidColor else NumericInputError.NotWholeNumber
        parsed == null || parsed > Int.MAX_VALUE -> NumericInputError.OutOfRange
        else -> null
    }
    val value: Int? = if (error == null) parsed?.toInt() else null
    val isValid: Boolean get() = value != null

    companion object {
        private val WHOLE = Regex("[0-9]+")
        private val RGB = Regex("[0-9a-fA-F]{6}")

        fun fromValue(field: SettingsNumericField, value: Int): SettingsNumericInput = SettingsNumericInput(
            field,
            if (field == SettingsNumericField.PlayerColor) value.toUInt().toString(16).uppercase().padStart(6, '0')
            else value.toString(),
        )
    }
}

package com.airtak.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.airtak.R

@Composable
internal fun SettingsNumberField(input: SettingsNumericInput, label: String, onText: (String) -> Unit) {
    val errorMessage = when (input.error) {
        NumericInputError.Empty -> stringResource(R.string.settings_number_required)
        NumericInputError.NotWholeNumber -> stringResource(R.string.settings_number_whole)
        NumericInputError.OutOfRange -> stringResource(R.string.settings_number_range, Int.MAX_VALUE)
        NumericInputError.InvalidColor -> stringResource(R.string.settings_color_invalid)
        null -> null
    }
    OutlinedTextField(
        value = input.text,
        onValueChange = onText,
        label = { Text(label) },
        isError = errorMessage != null,
        supportingText = if (errorMessage != null) { { Text(errorMessage) } } else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (input.field == SettingsNumericField.PlayerColor) KeyboardType.Ascii else KeyboardType.Number,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

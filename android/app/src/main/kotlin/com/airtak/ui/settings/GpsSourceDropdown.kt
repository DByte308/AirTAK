package com.airtak.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.airtak.R
import com.airtak.proto.GpsSource

internal val gpsSourceOptions = listOf(GpsSource.DEVICE_GPS, GpsSource.PHONE_GPS)

@StringRes
internal fun gpsSourceLabelResource(source: GpsSource): Int = when (source) {
    GpsSource.DEVICE_GPS -> R.string.settings_device_gps
    GpsSource.PHONE_GPS -> R.string.settings_phone_gps
    else -> R.string.settings_gps_source_unknown
}

@Composable
internal fun GpsSourceDropdown(value: GpsSource, onValue: (GpsSource) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(gpsSourceLabelResource(value)), modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            gpsSourceOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(gpsSourceLabelResource(option))) },
                    leadingIcon = {
                        if (value == option) Icon(Icons.Outlined.Check, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onValue(option)
                    },
                )
            }
        }
    }
}

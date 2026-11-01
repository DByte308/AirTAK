package com.airtak.ui

import com.airtak.R
import com.airtak.proto.GpsSource
import com.airtak.ui.settings.gpsSourceLabelResource
import com.airtak.ui.settings.gpsSourceOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GpsSourceDropdownTest {
    @Test fun offersOnlyRadioAndSmartphoneSources() {
        assertEquals(listOf(GpsSource.DEVICE_GPS, GpsSource.PHONE_GPS), gpsSourceOptions)
        assertFalse(gpsSourceOptions.contains(GpsSource.UNRECOGNIZED))
    }

    @Test fun radioSourceHasItsOwnTranslatedLabel() {
        assertEquals(R.string.settings_device_gps, gpsSourceLabelResource(GpsSource.DEVICE_GPS))
    }

    @Test fun smartphoneSourceHasItsOwnTranslatedLabel() {
        assertEquals(R.string.settings_phone_gps, gpsSourceLabelResource(GpsSource.PHONE_GPS))
    }

    @Test fun unknownSourceIsNotSilentlyShownAsRadioGps() {
        assertEquals(R.string.settings_gps_source_unknown, gpsSourceLabelResource(GpsSource.UNRECOGNIZED))
    }
}

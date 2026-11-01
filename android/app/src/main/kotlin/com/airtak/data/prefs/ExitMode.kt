package com.airtak.data.prefs

/** Exclusive user-requested exit triggers. Unknown stored values retain the original behaviour. */
enum class ExitMode {
    DoubleBack,
    Recents,
    ButtonOnly;

    val closesOnBack: Boolean get() = this == DoubleBack

    fun closesOnTaskRemoval(firmwareUpdateInFlight: Boolean): Boolean =
        this == Recents && !firmwareUpdateInFlight

    companion object {
        fun fromStored(value: String?): ExitMode =
            entries.firstOrNull { it.name == value } ?: DoubleBack
    }
}

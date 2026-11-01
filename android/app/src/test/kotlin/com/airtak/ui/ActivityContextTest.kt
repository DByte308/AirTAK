package com.airtak.ui

import android.content.Context
import android.content.ContextWrapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ActivityContextTest {
    @Test fun directActivityIsFound() {
        val activity = mockk<MainActivity>()
        assertSame(activity, activity.findMainActivity())
    }

    @Test fun nestedThemedWrappersAreUnwrapped() {
        val activity = mockk<MainActivity>()
        val inner = mockk<ContextWrapper> { every { baseContext } returns activity }
        val outer = mockk<ContextWrapper> { every { baseContext } returns inner }
        assertSame(activity, outer.findMainActivity())
    }

    @Test fun applicationContextHasNoActivity() {
        assertNull(mockk<Context>().findMainActivity())
    }

    @Test fun cyclicWrapperDoesNotLoopForever() {
        val wrapper = mockk<ContextWrapper>()
        every { wrapper.baseContext } returns wrapper
        assertNull(wrapper.findMainActivity())
    }
}

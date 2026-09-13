package com.example.ui.review

import org.junit.Assert.assertEquals
import org.junit.Test

/** The interval printed on the understanding buttons, which must agree with the message after rating. */
class IntervalButtonLabelTest {

    @Test
    fun `rounds to the nearest tenth instead of truncating`() {
        // A first Easy check-in lands at ~4.98 days. Truncation printed "4.9d" on the button while the
        // message for the very same review said "in 5 days", and every label under-reported.
        assertEquals("5d", intervalButtonLabel(4.9811))
        assertEquals("1.3d", intervalButtonLabel(1.2931))
        assertEquals("2.3d", intervalButtonLabel(2.3065))
        assertEquals("30.8d", intervalButtonLabel(30.8399))
        assertEquals("353.3d", intervalButtonLabel(353.2583))
    }

    @Test
    fun `whole days drop the decimal`() {
        assertEquals("1d", intervalButtonLabel(1.0))
        assertEquals("3d", intervalButtonLabel(3.0))
        assertEquals("4d", intervalButtonLabel(3.96))
        assertEquals("365d", intervalButtonLabel(365.0))
    }

    @Test
    fun `under a day shows hours`() {
        assertEquals("12h", intervalButtonLabel(0.5))
        assertEquals("<1h", intervalButtonLabel(0.02))
    }
}

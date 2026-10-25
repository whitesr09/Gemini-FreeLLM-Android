package com.nshd.geminifreellm.ui

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherIconTest {
    private val on = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    private val off = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    private val def = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

    @Test fun `fresh install shows current icon`() {
        assertEquals(LauncherIcon.Choice.CURRENT, LauncherIcon.choiceFor(def, def))
    }

    @Test fun `gold alias enabled selects gold`() {
        assertEquals(LauncherIcon.Choice.GOLD, LauncherIcon.choiceFor(on, off))
    }

    @Test fun `blue alias enabled selects blue`() {
        assertEquals(LauncherIcon.Choice.BLUE, LauncherIcon.choiceFor(off, on))
    }

    @Test fun `both aliases disabled falls back to current`() {
        assertEquals(LauncherIcon.Choice.CURRENT, LauncherIcon.choiceFor(off, off))
    }
}

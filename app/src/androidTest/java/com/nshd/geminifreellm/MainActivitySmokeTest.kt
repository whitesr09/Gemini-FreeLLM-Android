package com.nshd.geminifreellm

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchShowsOnboarding() {
        composeRule.onNodeWithText("Name your AI", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Continue", useUnmergedTree = true).assertIsDisplayed()
    }
}

package com.rknepp.parity

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rknepp.parity.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E2ETests {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000) {
        composeTestRule.waitUntil(timeoutMs) {
            try {
                composeTestRule
                    .onAllNodesWithText(text, substring = substring, useUnmergedTree = true)[0]
                    .assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }

    private fun register(username: String, displayName: String) {
        composeTestRule.onNodeWithText("Create an account").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Username").performTextReplacement(username)
        composeTestRule.onNodeWithText("Display name").performTextReplacement(displayName)
        composeTestRule.onNodeWithText("Password").performTextReplacement("password123")
        Espresso.closeSoftKeyboard()
        composeTestRule.onNodeWithText("Register").performClick()
        waitForText("Sign in")
    }

    private fun login(username: String) {
        composeTestRule.onNodeWithText("Username").performTextReplacement(username)
        composeTestRule.onNodeWithText("Password").performTextReplacement("password123")
        Espresso.closeSoftKeyboard()
        composeTestRule
            .onNode(hasText("Sign in") and hasClickAction(), useUnmergedTree = true)
            .performClick()
        // The bottom navigation bar marks a successful arrival at Home.
        waitForText("Relationships")
    }

    private fun logout() {
        composeTestRule.onAllNodesWithText("Settings", useUnmergedTree = true)[0].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Log out").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Yes, log out").performClick()
        waitForText("Sign in")
    }

    @Test
    fun testUnifiedE2EFlow() {
        val uniqueId = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("uniqueId", "test_" + System.currentTimeMillis())
        val aliceUsername = "alice_$uniqueId"
        val bobUsername = "bob_$uniqueId"

        // 1. Register both users.
        register(aliceUsername, "Alice Test")
        register(bobUsername, "Bob Test")

        // 2. Alice invites Bob (default currency USD).
        login(aliceUsername)
        composeTestRule.onAllNodesWithText("Relationships", useUnmergedTree = true)[0]
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Invite someone", substring = true).performClick()
        composeTestRule.onNodeWithText("Their username").performTextInput(bobUsername)
        Espresso.closeSoftKeyboard()
        composeTestRule.onNodeWithText("Send invite").performClick()
        waitForText("Invite sent")
        logout()

        // 3. Bob accepts and records an expense.
        login(bobUsername)
        composeTestRule.onAllNodesWithText("Relationships", useUnmergedTree = true)[0]
            .performClick()
        waitForText("Accept")
        composeTestRule.onNodeWithText("Accept").performClick()
        waitForText("Alice Test")
        composeTestRule.onNodeWithText("Alice Test").performClick()
        waitForText("Add expense")

        composeTestRule.onNodeWithText("Add expense").performClick()
        composeTestRule.onNodeWithText("Total amount").performTextInput("15.00")
        composeTestRule.onNodeWithText("Description").performTextInput("Dinner")
        Espresso.closeSoftKeyboard()
        composeTestRule.onNodeWithText("Save expense").performScrollTo().performClick()

        // Back on the detail screen the new entry appears via the
        // on-resume refresh.
        waitForText("Dinner")
    }
}

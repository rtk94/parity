package com.rknepp.parity

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import com.rknepp.parity.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E2ETests {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testUnifiedE2EFlow() {
        val uniqueId = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("uniqueId", "test_" + System.currentTimeMillis())
        val aliceUsername = "alice_$uniqueId"
        val bobUsername = "bob_$uniqueId"
        
        // 1. Register Alice
        composeTestRule.onNodeWithText("Create an account").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Username").performTextInput(aliceUsername)
        composeTestRule.onNodeWithText("Display name").performTextInput("Alice Test")
        composeTestRule.onNodeWithText("Password").performTextInput("password123")
        Espresso.closeSoftKeyboard()
        composeTestRule.onNodeWithText("Register").performClick()

        // Wait for Sign in
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onAllNodesWithText("Sign in", useUnmergedTree = true)[0].assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        
        // 2. Register Bob
        composeTestRule.onNodeWithText("Create an account").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Username").performTextReplacement(bobUsername)
        composeTestRule.onNodeWithText("Display name").performTextReplacement("Bob Test")
        composeTestRule.onNodeWithText("Password").performTextReplacement("password123")
        Espresso.closeSoftKeyboard()
        composeTestRule.onNodeWithText("Register").performClick()

        // Wait for Sign in again
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onAllNodesWithText("Sign in", useUnmergedTree = true)[0].assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        
        composeTestRule.onNodeWithText("Username").performTextReplacement(aliceUsername)
        composeTestRule.onNodeWithText("Password").performTextReplacement("password123")
        Espresso.closeSoftKeyboard()
        composeTestRule.onNode(androidx.compose.ui.test.hasText("Sign in") and androidx.compose.ui.test.hasClickAction(), useUnmergedTree = true).performClick()
        
        // Wait for Home
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onNodeWithText("View Relationships").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Alice logs out
        composeTestRule.onNodeWithText("Log out").performClick()

        // Wait for sign in screen
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onAllNodesWithText("Sign in", useUnmergedTree = true)[0].assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // 2. Register Bob
        composeTestRule.onNodeWithText("Create an account").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Username").performTextReplacement(bobUsername)
        composeTestRule.onNodeWithText("Display name").performTextInput("Bob Test")
        composeTestRule.onNodeWithText("Password").performTextInput("password123")
        Espresso.closeSoftKeyboard()
        composeTestRule.onNodeWithText("Register").performClick()

        // Login as Bob
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onAllNodesWithText("Sign in", useUnmergedTree = true)[0].assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        composeTestRule.onNodeWithText("Username").performTextReplacement(bobUsername)
        composeTestRule.onNodeWithText("Password").performTextInput("password123")
        Espresso.closeSoftKeyboard()
        composeTestRule.onAllNodesWithText("Sign in")[1].performClick()

        // Wait for Home
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onNodeWithText("View Relationships").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Bob logs out
        composeTestRule.onNodeWithText("Log out").performClick()

        // Wait for Sign in
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onAllNodesWithText("Sign in", useUnmergedTree = true)[0].assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // 3. Login Alice to create relationship and expense
        composeTestRule.onNodeWithText("Username").performTextReplacement(aliceUsername)
        composeTestRule.onNodeWithText("Password").performTextInput("password123")
        Espresso.closeSoftKeyboard()
        composeTestRule.onAllNodesWithText("Sign in")[1].performClick()

        // Wait for Home
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onNodeWithText("View Relationships").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Navigate to Relationships
        composeTestRule.onNodeWithText("View Relationships").performClick()
        composeTestRule.onNodeWithContentDescription("Add Relationship").performClick()

        composeTestRule.onNodeWithText("Counterparty Username").performTextInput(bobUsername)
        composeTestRule.onNodeWithText("Currency Code (e.g. USD)").performTextInput("USD")
        Espresso.closeSoftKeyboard()
        composeTestRule.onNodeWithText("Send Invite").performClick()
        
        // Wait for it to be created and to navigate back
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onNodeWithContentDescription("Add Relationship").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        
        // Alice goes to settings to log out
        composeTestRule.onAllNodesWithText("Settings", useUnmergedTree = true)[0].performClick()
        composeTestRule.onNodeWithText("Log Out").performClick()

        // Wait for Sign in
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onAllNodesWithText("Sign in", useUnmergedTree = true)[0].assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // 4. Login Bob to accept relationship and add expense
        composeTestRule.onNodeWithText("Username").performTextReplacement(bobUsername)
        composeTestRule.onNodeWithText("Password").performTextReplacement("password123")
        Espresso.closeSoftKeyboard()
        composeTestRule.onNode(androidx.compose.ui.test.hasText("Sign in") and androidx.compose.ui.test.hasClickAction(), useUnmergedTree = true).performClick()

        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onNodeWithText("View Relationships").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        composeTestRule.onNodeWithText("View Relationships").performClick()
        
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onNodeWithText("Accept").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        composeTestRule.onNodeWithText("Accept").performClick()

        // Bob clicks the relationship to add expense
        composeTestRule.onNodeWithText(aliceUsername, substring = true).performClick()

        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onNodeWithContentDescription("Add Expense").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Add Expense
        composeTestRule.onNodeWithContentDescription("Add Expense").performClick()
        
        composeTestRule.onNodeWithText("Amount").performTextInput("1500")
        composeTestRule.onNodeWithText("Description").performTextInput("Dinner")
        Espresso.closeSoftKeyboard()
        composeTestRule.onNodeWithText("Create").performClick()
        
        // Wait for success
        composeTestRule.waitUntil(10000) {
            try {
                composeTestRule.onNodeWithText("Dinner").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }
}

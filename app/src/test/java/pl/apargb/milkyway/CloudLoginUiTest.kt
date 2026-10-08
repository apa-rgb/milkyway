package pl.apargb.milkyway

import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class CloudLoginUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun numberedLoginSelectsAccount10RequiresPasswordAndClearsItAfterSubmission() {
        var submitted: Pair<String, String>? = null
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme(colorScheme = MilkywayColors) {
            CloudLoginPage(CloudSessionState(configured = true)) { number, password -> submitted = number to password }
        } } }
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("login-account").performClick()
        compose.onNodeWithTag("login-account-10").performScrollTo().performClick()
        compose.onNodeWithTag("login-account").assert(hasText("10"))
        compose.onNodeWithTag("login-password").performTextInput("test-password")
        compose.onNodeWithTag("login-submit").performClick()
        assertEquals("10" to "test-password", submitted)
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        assertEquals("konto10@demo-milkyway.accounts.invalid", operatorEmail("10", "demo-milkyway"))
        assertThrows(IllegalArgumentException::class.java) { operatorEmail("11", "demo-milkyway") }
    }
}

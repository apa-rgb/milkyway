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
        var submitted: Triple<String, String, String>? = null
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme(colorScheme = MilkywayColors) {
            CloudLoginPage(CloudSessionState(configured = true)) { number, password, name, _ -> submitted = Triple(number, password, name) }
        } } }
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("login-account").performClick()
        compose.onNodeWithTag("login-account-10").performScrollTo().performClick()
        compose.onNodeWithTag("login-account").assert(hasText("oper10"))
        compose.onNodeWithTag("login-password").performTextInput("test-password")
        compose.onNodeWithTag("login-submit").performScrollTo().performClick()
        assertEquals(Triple("10", "test-password", ""), submitted)
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        assertEquals("konto10@demo-milkyway.accounts.invalid", operatorEmail("10", "demo-milkyway"))
        assertThrows(IllegalArgumentException::class.java) { operatorEmail("21", "demo-milkyway") }
    }

    @Test fun optionalNameIsSubmittedWithTheSelectedAccountAndPasswordIsStillCleared() {
        var submitted: Triple<String, String, String>? = null
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme(colorScheme = MilkywayColors) {
            CloudLoginPage(CloudSessionState(configured = true)) { number, password, name, _ -> submitted = Triple(number, password, name) }
        } } }
        compose.onNodeWithTag("login-name").performTextInput("  Łukasz  ")
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("login-password").performTextInput("test-password")
        compose.onNodeWithTag("login-submit").performScrollTo().performClick()
        assertEquals(Triple("01", "test-password", "Łukasz"), submitted)
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("login-name").assert(hasText("  Łukasz  "))
    }

    @Test fun account20UsesSixCharacterLoginAndEmailOnANewDevice() {
        var submitted: List<String>? = null
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme(colorScheme = MilkywayColors) {
            CloudLoginPage(CloudSessionState(configured = true)) { number, password, name, email -> submitted = listOf(number, password, name, email) }
        } } }
        compose.onNodeWithTag("login-account").performClick()
        compose.onNodeWithTag("login-account-20").performScrollTo().performClick()
        compose.onNodeWithTag("login-account").assert(hasText("oper20"))
        compose.onNodeWithTag("login-email").performScrollTo().performTextInput("anna@example.com")
        compose.onNodeWithTag("login-password").performScrollTo().performTextInput("Chosen88")
        compose.onNodeWithTag("login-submit").performScrollTo().performClick()
        assertEquals(listOf("20", "Chosen88", "", "anna@example.com"), submitted)
        assertEquals("konto20@demo-milkyway.accounts.invalid", operatorEmail("20", "demo-milkyway"))
    }

    @Test fun passwordChangeRequiresEightMatchingCharactersAndRecoveryEmailAndClearsPasswords() {
        var submitted: List<String>? = null
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme(colorScheme = MilkywayColors) {
            CloudPasswordPage(CloudSessionState(uid = "test", number = "01", requiresPasswordChange = true),
                { current, next, email, result -> submitted = listOf(current, next, email); result("Testowa odpowiedź") }, {})
        } } }
        compose.onNodeWithTag("password-save").assertIsNotEnabled()
        compose.onNodeWithTag("password-current").performTextInput("Initial8")
        compose.onNodeWithTag("password-new").performTextInput("Chosen88")
        compose.onNodeWithTag("password-repeat").performTextInput("Wrong888")
        compose.onNodeWithTag("password-email").performScrollTo().performTextInput("anna@example.com")
        compose.onNodeWithTag("password-save").assertIsNotEnabled()
        compose.onNodeWithTag("password-repeat").performScrollTo().performTextReplacement("Chosen88")
        compose.onNodeWithTag("password-save").performScrollTo().performClick()
        assertEquals(listOf("Initial8", "Chosen88", "anna@example.com"), submitted)
        compose.onNodeWithTag("password-save").assertIsNotEnabled()
        compose.onNodeWithTag("password-current").assert(hasText(""))
    }

    @Test fun passwordResetAsksForEmailAndShowsGenericConfirmation() {
        var address: String? = null
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme(colorScheme = MilkywayColors) {
            CloudLoginPage(CloudSessionState(configured = true),
                onReset = { email, done -> address = email; done("Jeśli e-mail jest przypisany do konta, otrzymasz link.") }) { _, _, _, _ -> }
        } } }
        compose.onNodeWithTag("login-reset").performScrollTo().performClick()
        compose.onNodeWithTag("reset-send").assertIsNotEnabled()
        compose.onNodeWithTag("reset-email").performTextInput("anna@example.com")
        compose.onNodeWithTag("reset-send").performClick()
        assertEquals("anna@example.com", address)
        compose.onNodeWithTag("reset-message").assertTextContains("Jeśli e-mail", substring = true)
    }
}

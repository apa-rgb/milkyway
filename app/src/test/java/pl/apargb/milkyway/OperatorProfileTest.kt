package pl.apargb.milkyway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OperatorProfileTest {
    @Test fun namesAreRestoredForTheirOwnVerifiedUserAndNeverUsedForSignedOutSessions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = OperatorProfile(context)
        profile.save("operator-01", "  Łukasz\n  ")
        profile.save("operator-02", "Anna")
        val restored = OperatorProfile(context)
        assertEquals("Łukasz", restored.name("operator-01"))
        assertEquals("Anna", restored.name("operator-02"))
        assertEquals("", restored.name("new-operator"))
        assertNull(CloudSessionState(displayName = "Anna").noteAuthor)
        assertEquals("Anna", CloudSessionState(uid = "operator-02", number = "02", displayName = restored.name("operator-02")).noteAuthor)
        assertEquals("Konto 03", CloudSessionState(uid = "operator-03", number = "03").noteAuthor)
        profile.save("operator-02", "")
        assertEquals("", restored.name("operator-02"))
    }
}

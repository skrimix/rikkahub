package me.rerere.rikkahub.ui.pages.assistant.detail

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Rule
import org.junit.Test

class ShizukuToolSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enableAndApprovalSettingsAreIndependent() {
        compose.setContent {
            var assistant by remember { mutableStateOf(Assistant()) }
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        ShizukuToolSettings(assistant) { assistant = it }
                    }
                }
            }
        }
        compose.onAllNodes(isToggleable())[0].assertIsOff().performClick()
        compose.onNodeWithText("Require command approval").assertExists()
        compose.onAllNodes(isToggleable())[1].assertIsOn().performClick()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNodeWithText("Require command approval").assertDoesNotExist()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[1].assertIsOff().performClick()
        val file = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("shizuku-settings.png")
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

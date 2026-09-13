package com.example

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.today.StudyUnitCard
import com.example.data.local.entity.StudyUnitEntity
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun testStudyUnitCard_screenshot() {
    val mockUnit = StudyUnitEntity(
        id = 1,
        title = "Myocardial Infarction",
        studyType = "Pathology",
        recallPrompt = "Key ECG findings and troponin timing?",
        notes = "ST elevation, T wave inversion. Troponin rises at 3-12h...",
        highYield = true,
        state = "Building",
        nextReviewAt = System.currentTimeMillis()
    )

    composeTestRule.setContent {
      MyApplicationTheme {
        StudyUnitCard(unit = mockUnit)
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/study_unit_card.png")
  }
}

package dev.sharedlists.android

import android.content.Intent
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedListsActivityUiTest {
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @After
    fun resetDisplaySize() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("wm size reset").close()
    }

    @Test
    fun narrowLiveFixtureExposesAccessibleListAndEditableDetail() {
        val scenario = launchFixture("ready-populated")
        try {
            assertTrue(device.wait(Until.hasObject(By.desc("Shared Lists test fixture: ready populated")), TIMEOUT_MILLIS))
            assertTrue(device.hasObject(By.desc("Groceries")))
            device.findObject(By.desc("Groceries")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("Add item")), TIMEOUT_MILLIS))
            assertTrue(device.hasObject(By.desc("Edit Milk")))
            device.findObject(By.desc("Add item")).click()
            val input = device.wait(Until.findObject(By.clazz(EditText::class.java)), TIMEOUT_MILLIS)
            input.click()
            assertTrue(input.isFocused)
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("Add item")), TIMEOUT_MILLIS))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun narrowCachedFixtureShowsStatusAndGatesEdits() {
        val scenario = launchFixture("cached-read-only")
        try {
            assertTrue(device.wait(Until.hasObject(By.desc("Shared Lists test fixture: cached read only")), TIMEOUT_MILLIS))
            assertTrue(device.hasObject(By.text("Cached data is read-only while synchronization recovers.")))
            assertFalse(device.hasObject(By.desc("Create shared list")))
            device.findObject(By.desc("Groceries")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("Edit Milk")), TIMEOUT_MILLIS))
            assertFalse(device.findObject(By.desc("Edit Milk")).isEnabled)
            assertFalse(device.hasObject(By.desc("Add item")))
            assertFalse(device.hasObject(By.desc("Delete list")))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun wideLiveFixtureShowsListRailAndSelectedDetail() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("wm size 2400x1080").close()
        val scenario = launchFixture("ready-populated")
        try {
            assertTrue(device.wait(Until.hasObject(By.desc("Shared Lists test fixture: ready populated")), TIMEOUT_MILLIS))
            assertTrue(device.wait(Until.hasObject(By.desc("Shared lists and selected list detail")), TIMEOUT_MILLIS))
            assertTrue(device.hasObject(By.text("Groceries")))
            assertTrue(device.hasObject(By.desc("Edit Milk")))
            assertTrue(device.hasObject(By.desc("Mark Groceries")))
            assertTrue(device.hasObject(By.desc("Add item")))
        } finally {
            scenario.close()
        }
    }

    private fun launchFixture(selector: String): ActivityScenario<SharedListsActivity> {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent().setClassName(targetContext.packageName, SharedListsActivity::class.java.name)
            .putExtra(TEST_PRESENTATION, selector)
        return ActivityScenario.launch(intent)
    }

    private companion object {
        const val TEST_PRESENTATION = "dev.sharedlists.android.TEST_PRESENTATION"
        const val TIMEOUT_MILLIS = 5_000L
    }
}

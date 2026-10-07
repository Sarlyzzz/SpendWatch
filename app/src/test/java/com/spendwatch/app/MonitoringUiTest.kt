package com.spendwatch.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import com.spendwatch.app.notifications.MonitoringDiagnostics
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = SpendWatchApplication::class)
class MonitoringUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun diagnosticCallbackUpdatesMonitoringWithoutAnotherTap() {
        compose.onNodeWithText("监控").performScrollTo().performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))[1]
            .performScrollToNode(hasText("支付宝"))
        compose.runOnIdle {
            MonitoringDiagnostics.record(compose.activity, "com.eg.android.AlipayGphone", "测试回调已到达")
        }
        compose.onNodeWithText("测试回调已到达", substring = true).assertExists()
    }
}

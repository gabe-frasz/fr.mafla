package fr.mafla.helper

import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import fr.mafla.R
import fr.mafla.data.PanelWidget
import fr.mafla.data.Prefs

class PinItemActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.setBackgroundDrawable(null)

        val launcherApps = getSystemService(LauncherApps::class.java)
        val pinItemRequest = launcherApps.getPinItemRequest(intent)

        when (pinItemRequest != null) {
            true -> handleRequestType(pinItemRequest)
            false -> showToast(R.string.invalid_pin_request)
        }

        finish()
    }

    private fun handleRequestType(pinItemRequest: LauncherApps.PinItemRequest) {
        when (pinItemRequest.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT ->
                handleShortcutRequest(pinItemRequest)

            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET ->
                handleWidgetRequest(pinItemRequest)

            else -> showToast(R.string.pin_action_not_supported)
        }
    }

    // An app asked to add one of its widgets: it goes at the bottom of the widgets panel
    private fun handleWidgetRequest(pinItemRequest: LauncherApps.PinItemRequest) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val info = pinItemRequest.getAppWidgetProviderInfo(this)
        if (info == null) {
            showToast(R.string.widget_add_failed)
            return
        }
        val host = MaflaWidgetHost.get(this)
        val id = host.allocateAppWidgetId()
        val accepted = runCatching {
            pinItemRequest.accept(bundleOf(AppWidgetManager.EXTRA_APPWIDGET_ID to id))
        }.getOrDefault(false)
        if (!accepted) {
            host.deleteAppWidgetId(id)
            showToast(R.string.widget_add_failed)
            return
        }
        Prefs(this).addPanelWidget(PanelWidget(id, WidgetSizes.defaultHeightDp(info, resources.displayMetrics.density)))
        showToast(R.string.widget_added)
    }

    private fun handleShortcutRequest(pinItemRequest: LauncherApps.PinItemRequest) {
        val shortcutInfo = pinItemRequest.shortcutInfo
        if (shortcutInfo != null) {
            val success = runCatching { pinItemRequest.accept() }.getOrDefault(false)
            val message = when (success) {
                true -> R.string.shortcut_pinned
                false -> R.string.shortcut_pin_failed
            }
            showToast(message)
        } else {
            showToast(R.string.invalid_shortcut)
        }
    }
}

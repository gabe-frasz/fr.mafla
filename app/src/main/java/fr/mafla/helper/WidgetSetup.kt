package fr.mafla.helper

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import fr.mafla.R
import fr.mafla.data.Constants
import fr.mafla.data.Prefs

/**
 * Takes a widget picked in the widget picker through what the system needs before it can
 * show: permission to bind it (a system dialog, the first time for each app) and the widget's
 * own setup screen, if it has one. [onAdded] runs once it's ready; backing out anywhere frees
 * its id. Create it in the activity's onCreate, so the permission result can come back.
 */
class WidgetSetup(
    private val activity: ComponentActivity,
    private val onAdded: (id: Int, info: AppWidgetProviderInfo) -> Unit,
) {
    private val prefs = Prefs(activity)
    private val host get() = MaflaWidgetHost.get(activity)
    private val manager get() = AppWidgetManager.getInstance(activity)

    private val bindRequest = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = prefs.pendingWidgetId
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) configureOrFinish(id) else cancel(id)
    }

    fun add(info: AppWidgetProviderInfo) {
        // One left over from a setup that never came back
        prefs.pendingWidgetId.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }?.let { cancel(it) }

        val id = host.allocateAppWidgetId()
        prefs.pendingWidgetId = id
        val bound = try {
            manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (e: Exception) {
            false
        }
        if (bound) {
            configureOrFinish(id)
            return
        }
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        try {
            bindRequest.launch(intent)
        } catch (e: Exception) {
            fail(id)
        }
    }

    /** Result of the widget's setup screen; the activity forwards it. */
    fun onConfigureResult(resultCode: Int) {
        val id = prefs.pendingWidgetId
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (resultCode == Activity.RESULT_OK) finish(id) else cancel(id)
    }

    private fun configureOrFinish(id: Int) {
        val info = manager.getAppWidgetInfo(id) ?: return fail(id)
        val setupOptional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure == null || setupOptional) {
            finish(id)
            return
        }
        try {
            host.startAppWidgetConfigureActivityForResult(
                activity, id, 0, Constants.REQUEST_CODE_CONFIGURE_WIDGET, null
            )
        } catch (e: Exception) {
            fail(id)
        }
    }

    private fun finish(id: Int) {
        prefs.pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        val info = manager.getAppWidgetInfo(id) ?: return fail(id)
        onAdded(id, info)
    }

    private fun cancel(id: Int) {
        prefs.pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        host.deleteAppWidgetId(id)
    }

    private fun fail(id: Int) {
        cancel(id)
        activity.showToast(R.string.widget_add_failed)
    }
}

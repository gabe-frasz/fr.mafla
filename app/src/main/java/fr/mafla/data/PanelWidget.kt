package fr.mafla.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A widget placed on the widgets panel: the system's widget id and the height the user gave
 * it, in dp. Panel widgets always take the full width, so the height is all that's stored.
 */
data class PanelWidget(
    val id: Int,
    val heightDp: Int,
)

object PanelWidgets {

    fun encode(widgets: List<PanelWidget>): String {
        val array = JSONArray()
        widgets.forEach { array.put(JSONObject().put("id", it.id).put("height", it.heightDp)) }
        return array.toString()
    }

    /** Reads what [encode] wrote. Broken entries and repeated ids are dropped. */
    fun decode(json: String?): List<PanelWidget> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val seen = HashSet<Int>()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optInt("id", 0)
                // 0 is AppWidgetManager.INVALID_APPWIDGET_ID
                if (id <= 0 || !seen.add(id)) return@mapNotNull null
                PanelWidget(id = id, heightDp = item.optInt("height", 0).coerceAtLeast(0))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}

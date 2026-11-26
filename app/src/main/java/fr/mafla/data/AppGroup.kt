package fr.mafla.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A user-defined group of apps, shown as a tab above the app list.
 * [members] holds member keys (see [AppGroups.memberKey]), so a group survives app renames.
 */
data class AppGroup(
    val id: String,
    val name: String,
    val members: Set<String>,
)

object AppGroups {

    fun newId(): String = UUID.randomUUID().toString()

    /** Stable key of an app or pinned shortcut inside a group, or null if it can't be grouped. */
    fun memberKey(app: AppModel): String? = when (app) {
        is AppModel.App -> app.appPackage + "|" + app.user.toString()
        is AppModel.PinnedShortcut -> "shortcut|" + app.identity
        is AppModel.PrivateSpaceHeader -> null
    }

    fun isMember(members: Set<String>, app: AppModel): Boolean =
        memberKey(app)?.let { it in members } ?: false

    fun encode(groups: List<AppGroup>): String {
        val array = JSONArray()
        groups.forEach { group ->
            val members = JSONArray()
            group.members.forEach { members.put(it) }
            array.put(
                JSONObject()
                    .put("id", group.id)
                    .put("name", group.name)
                    .put("members", members)
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<AppGroup> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val members = item.optJSONArray("members")
                AppGroup(
                    id = item.optString("id").ifBlank { newId() },
                    name = item.optString("name"),
                    members = (0 until (members?.length() ?: 0))
                        .mapNotNull { members?.optString(it)?.takeIf { key -> key.isNotBlank() } }
                        .toSet(),
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}

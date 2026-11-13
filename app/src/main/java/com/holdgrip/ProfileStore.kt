package com.holdgrip

import android.content.Context
import java.util.Base64
import java.util.UUID

data class GestureProfile(
    val id: String,
    val name: String,
    val ax: Float,
    val ay: Float,
    val bx: Float,
    val by: Float,
    val swipeMs: Int
)

/**
 * Stores named gesture profiles alongside the legacy settings.
 * Existing installations are migrated on first access without changing their current gesture.
 */
class ProfileStore(context: Context, private val prefs: Prefs) {
    private val sp = context.getSharedPreferences("holdgrip", Context.MODE_PRIVATE)

    init {
        if (readProfiles().isEmpty()) {
            val initial = GestureProfile(
                id = UUID.randomUUID().toString(),
                name = "پروفایل اصلی",
                ax = prefs.ax, ay = prefs.ay, bx = prefs.bx, by = prefs.by,
                swipeMs = prefs.swipeMs
            )
            writeProfiles(listOf(initial))
            sp.edit().putString(KEY_ACTIVE, initial.id).apply()
        } else if (active() == null) {
            sp.edit().putString(KEY_ACTIVE, readProfiles().first().id).apply()
        }
    }

    fun all(): List<GestureProfile> = readProfiles()

    fun active(): GestureProfile? {
        val items = readProfiles()
        val id = sp.getString(KEY_ACTIVE, null)
        return items.firstOrNull { it.id == id } ?: items.firstOrNull()
    }

    fun saveCurrent() {
        val current = active() ?: return
        val updated = current.copy(
            ax = prefs.ax.coerceIn(0f, 1f),
            ay = prefs.ay.coerceIn(0f, 1f),
            bx = prefs.bx.coerceIn(0f, 1f),
            by = prefs.by.coerceIn(0f, 1f),
            swipeMs = prefs.swipeMs.coerceIn(50, 3000)
        )
        writeProfiles(readProfiles().map { if (it.id == current.id) updated else it })
    }

    fun create(name: String): GestureProfile {
        saveCurrent()
        val source = active() ?: error("No active profile")
        val created = source.copy(id = UUID.randomUUID().toString(), name = name.trim())
        writeProfiles(readProfiles() + created)
        activateWithoutSaving(created)
        return created
    }

    fun renameActive(name: String): Boolean {
        val current = active() ?: return false
        val clean = name.trim()
        if (clean.isEmpty()) return false
        writeProfiles(readProfiles().map { if (it.id == current.id) it.copy(name = clean) else it })
        return true
    }

    fun switchTo(id: String): Boolean {
        val target = readProfiles().firstOrNull { it.id == id } ?: return false
        saveCurrent()
        activateWithoutSaving(target)
        return true
    }

    fun deleteActive(): Boolean {
        val items = readProfiles()
        if (items.size <= 1) return false
        val current = active() ?: return false
        val remaining = items.filterNot { it.id == current.id }
        writeProfiles(remaining)
        activateWithoutSaving(remaining.first())
        return true
    }

    fun exportBackup(): String {
        saveCurrent()
        val activeId = active()?.id ?: ""
        val lines = mutableListOf(HEADER, "A|$activeId")
        readProfiles().forEach { p ->
            val encodedName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(p.name.toByteArray(Charsets.UTF_8))
            lines += listOf("P", p.id, encodedName, p.ax, p.ay, p.bx, p.by, p.swipeMs).joinToString("|")
        }
        lines += listOf(
            "S", prefs.btnX, prefs.btnY, prefs.buttonSizeDp, prefs.buttonAlpha,
            prefs.locked, prefs.showButton
        ).joinToString("|")
        return lines.joinToString("\n")
    }

    fun importBackup(raw: String): Boolean {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != HEADER) return false

        var importedActiveId: String? = null
        var settings: List<String>? = null
        val imported = mutableListOf<GestureProfile>()
        try {
            lines.drop(1).forEach { line ->
                val p = line.split("|")
                when (p.firstOrNull()) {
                    "A" -> importedActiveId = p.getOrNull(1)
                    "P" -> {
                        if (p.size != 8) return false
                        val name = String(Base64.getUrlDecoder().decode(p[2]), Charsets.UTF_8)
                        imported += GestureProfile(
                            id = p[1], name = name,
                            ax = p[3].toFloat().coerceIn(0f, 1f),
                            ay = p[4].toFloat().coerceIn(0f, 1f),
                            bx = p[5].toFloat().coerceIn(0f, 1f),
                            by = p[6].toFloat().coerceIn(0f, 1f),
                            swipeMs = p[7].toInt().coerceIn(50, 3000)
                        )
                    }
                    "S" -> settings = p
                }
            }
        } catch (_: Exception) {
            return false
        }
        if (imported.isEmpty()) return false

        val chosen = imported.firstOrNull { it.id == importedActiveId } ?: imported.first()
        settings?.let { s ->
            if (s.size != 7) return false
            try {
                prefs.btnX = s[1].toFloat().coerceIn(0f, 1f)
                prefs.btnY = s[2].toFloat().coerceIn(0f, 1f)
                prefs.buttonSizeDp = s[3].toInt().coerceIn(32, 88)
                prefs.buttonAlpha = s[4].toFloat().coerceIn(0.2f, 1f)
                prefs.locked = s[5].toBooleanStrict()
                prefs.showButton = s[6].toBooleanStrict()
            } catch (_: Exception) {
                return false
            }
        }
        writeProfiles(imported)
        activateWithoutSaving(chosen)
        return true
    }

    private fun activateWithoutSaving(profile: GestureProfile) {
        prefs.ax = profile.ax
        prefs.ay = profile.ay
        prefs.bx = profile.bx
        prefs.by = profile.by
        prefs.swipeMs = profile.swipeMs
        sp.edit().putString(KEY_ACTIVE, profile.id).apply()
    }

    private fun readProfiles(): List<GestureProfile> {
        val raw = sp.getString(KEY_PROFILES, null) ?: return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split("|")
            if (p.size != 7) return@mapNotNull null
            try {
                GestureProfile(
                    id = p[0],
                    name = String(Base64.getUrlDecoder().decode(p[1]), Charsets.UTF_8),
                    ax = p[2].toFloat().coerceIn(0f, 1f),
                    ay = p[3].toFloat().coerceIn(0f, 1f),
                    bx = p[4].toFloat().coerceIn(0f, 1f),
                    by = p[5].toFloat().coerceIn(0f, 1f),
                    swipeMs = p[6].toInt().coerceIn(50, 3000)
                )
            } catch (_: Exception) { null }
        }.distinctBy { it.id }.toList()
    }

    private fun writeProfiles(items: List<GestureProfile>) {
        val raw = items.joinToString("\n") { p ->
            val name = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(p.name.toByteArray(Charsets.UTF_8))
            listOf(p.id, name, p.ax, p.ay, p.bx, p.by, p.swipeMs).joinToString("|")
        }
        sp.edit().putString(KEY_PROFILES, raw).apply()
    }

    companion object {
        private const val KEY_PROFILES = "gesture_profiles_v1"
        private const val KEY_ACTIVE = "active_gesture_profile_v1"
        private const val HEADER = "HOLDGRIP_BACKUP_V1"
    }
}

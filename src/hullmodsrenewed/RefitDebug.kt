package hullmodsrenewed

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.UIComponentAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import hullmodsrenewed.uiframework.ReflectionUtils.invoke
import hullmodsrenewed.uiframework.getChildrenCopy
import hullmodsrenewed.uiframework.height
import hullmodsrenewed.uiframework.width
import hullmodsrenewed.uiframework.x
import hullmodsrenewed.uiframework.y

/**
 * Temporary developer aid: dumps the picker dialog's UI component tree (class, screen rect, any
 * text/data) to starsector.log. Used to find exact geometry for panel placement and to locate the
 * vanilla bottom facet bar we want to hide. Flip [ENABLED] off (or delete this file) before release.
 */
object RefitDebug {

    const val ENABLED = false

    private val log = Global.getLogger(RefitDebug::class.java)

    fun dumpTree(root: UIComponentAPI, label: String) {
        if (!ENABLED) return
        log.info("===== HMR UI dump: $label =====")
        walk(root, 0)
        log.info("===== HMR UI dump end =====")
    }

    private fun walk(c: UIComponentAPI, depth: Int) {
        if (depth > 14) return
        val cls = c.javaClass.name.substringAfterLast('.')
        val rect = runCatching { "x=${c.x.toInt()} y=${c.y.toInt()} w=${c.width.toInt()} h=${c.height.toInt()}" }
            .getOrDefault("rect=?")
        val text = runCatching { c.invoke("getText") as? String }.getOrNull()
        val data = runCatching { c.invoke("getData") }.getOrNull()?.javaClass?.simpleName
        val extra = buildString {
            if (!text.isNullOrBlank()) append(" text='$text'")
            if (data != null) append(" data=$data")
        }
        log.info("  ".repeat(depth) + "$cls [$rect]$extra")
        if (c is UIPanelAPI) for (child in runCatching { c.getChildrenCopy() }.getOrDefault(emptyList())) {
            walk(child, depth + 1)
        }
    }
}

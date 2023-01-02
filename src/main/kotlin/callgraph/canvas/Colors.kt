package callgraph.canvas

import com.intellij.ui.JBColor
import java.awt.Color

enum class Colors(val color: JBColor) {
    BACKGROUND_COLOR(JBColor(Color(0xFDFEFF), Color(0x292B2D))),
    UN_HIGHLIGHTED_COLOR(JBColor(Color(0xC6C8CA), Color(0x585A5C))),
    NEUTRAL_COLOR(JBColor(Color(0x626466), Color(0x949698))),
    HIGHLIGHTED_COLOR(JBColor(Color(0x4285F4), Color(0x589DEF))),
    HIGHLIGHTED_BACKGROUND_COLOR(JBColor(Color(0xFFFF00), Color(0xFFFF00))),
    UPSTREAM_COLOR(JBColor(Color(0xFBBC05), Color(0xBE9117))),
    DOWNSTREAM_COLOR(JBColor(Color(0x34A853), Color(0x538863))),

    DEEP_BLUE(JBColor(Color(0x0000FF), Color(0x0000FF))),
    BLUE(JBColor(Color(0x0088FF), Color(0x0088FF))),
    LIGHT_BLUE(JBColor(Color(0x00FFFF), Color(0x00FFFF))),
    CYAN(JBColor(Color(0x00FF88), Color(0x00FF88))),
    GREEN(JBColor(Color(0x00FF00), Color(0x00FF00))),
    LIGHT_GREEN(JBColor(Color(0x88FF00), Color(0x88FF00))),
    YELLOW(JBColor(Color(0xFFFF00), Color(0xFFFF00))),
    LIGHT_ORANGE(JBColor(Color(0xFFAA00), Color(0xFFAA00))),
    ORANGE(JBColor(Color(0xFF6600), Color(0xFF6600))),
    RED(JBColor(Color(0xFF0000), Color(0xFF0000)))
}

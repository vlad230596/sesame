package com.vlad230596.sesame.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vlad230596.sesame.ui.theme.Palette
import com.vlad230596.sesame.ui.theme.SesameTheme
import org.junit.Assert.fail

/**
 * Условия, при которых подписи начинают налезать друг на друга: обычный экран и
 * узкий (320 dp — маленький телефон) с крупным шрифтом системы (×1.3).
 */
enum class Stress(val width: Dp?, val fontScale: Float) {
    NORMAL(null, 1f),
    NARROW_LARGE_FONT(320.dp, 1.3f),
}

@Composable
fun StressFrame(stress: Stress, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, stress.fontScale)) {
        SesameTheme {
            val modifier = Modifier.background(Palette.Background).fillMaxHeight()
            Box(if (stress.width != null) modifier.requiredWidth(stress.width) else modifier) { content() }
        }
    }
}

private val hasText = SemanticsMatcher("has Text") {
    it.config.contains(SemanticsProperties.Text) && !it.config.contains(SemanticsProperties.EditableText)
}

/**
 * Ни одна подпись на экране не налезает на другую.
 *
 * Сравниваются рамки всех текстовых узлов несвёрнутого дерева попарно; вложенные
 * (подпись внутри кнопки и сама кнопка) не в счёт. Допуск [tolerancePx] —
 * на сглаживание краёв: касание рамок пересечением не считается.
 */
fun ComposeContentTestRule.assertNoOverlappingTexts(tolerancePx: Float = 2f) {
    waitForIdle()
    val nodes = onAllNodes(hasText, useUnmergedTree = true).fetchSemanticsNodes()
        .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
    val problems = mutableListOf<String>()
    for (i in nodes.indices) {
        for (j in i + 1 until nodes.size) {
            val a = nodes[i]
            val b = nodes[j]
            if (a.root != b.root || a.isRelatedTo(b)) continue
            val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
            if (overlap.width > tolerancePx && overlap.height > tolerancePx) {
                problems += "«${a.text()}» ${a.boundsInRoot.short()} × «${b.text()}» ${b.boundsInRoot.short()}"
            }
        }
    }
    if (problems.isNotEmpty()) fail("Подписи налезают друг на друга:\n" + problems.joinToString("\n"))
}

private fun SemanticsNode.isRelatedTo(other: SemanticsNode): Boolean =
    generateSequence(this) { it.parent }.any { it.id == other.id } ||
        generateSequence(other) { it.parent }.any { it.id == id }

private fun SemanticsNode.text(): String =
    config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

private fun Rect.short(): String = "[${left.toInt()},${top.toInt()}–${right.toInt()},${bottom.toInt()}]"

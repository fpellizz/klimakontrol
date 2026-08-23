package net.klimakontrol.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/**
 * `clickable` con micro-feedback: una leggera pressione (scala) al tocco, senza ripple —
 * un solo linguaggio tattile per tutte le superfici del "Quadrante".
 *
 * Va messo per PRIMO nella catena dei modifier (dopo eventuale weight/size), così la scala
 * agisce sull'intero elemento, sfondo compreso, senza spostare il layout.
 */
@Composable
fun Modifier.pressClickable(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.965f else 1f, label = "press")
    return this
        .scale(scale)
        .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
}

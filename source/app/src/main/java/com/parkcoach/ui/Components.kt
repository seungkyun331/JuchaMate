package com.parkcoach.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A hairline of light around dark surfaces, which is what lifts them off the background. */
val Hairline: Color get() = Palette.Text.copy(alpha = 0.09f)

/** Dark, see-through and softly edged: for anything laid over the moving picture. */
fun Modifier.glass(radius: Int = 30): Modifier =
    background(Palette.Glass, RoundedCornerShape(radius.dp)).border(1.dp, Hairline, RoundedCornerShape(radius.dp))

/** The one primary action on a screen: a white capsule, large enough to hit without aiming. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.Text,
    textColor: Color = Palette.OnBright,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 80.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = textColor,
            disabledContainerColor = Palette.PanelHigh,
            disabledContentColor = Palette.Dim,
        ),
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 20.dp),
    ) {
        Text(text, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/** Secondary actions: a quiet grey capsule. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textColor: Color = Palette.Text,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 66.dp),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, Hairline),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Soft,
            contentColor = textColor,
            disabledContainerColor = Palette.Soft.copy(alpha = 0.04f),
            disabledContentColor = Palette.Dim.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = Palette.Panel,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = color, shape = RoundedCornerShape(34.dp), border = BorderStroke(1.dp, Hairline), modifier = modifier) {
        Column(Modifier.padding(30.dp), content = content)
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, textColor: Color = Palette.OnBright) {
    Box(
        modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(text, color = textColor, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Title row with an optional back button, then the screen's content. */
@Composable
fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                QuietButton("‹  뒤로", onBack)
                Spacer(Modifier.width(24.dp))
            }
            Text(
                title,
                color = Palette.Text,
                fontSize = 42.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(22.dp))
        content()
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Palette.Dim) {
    Text(text, color = color, fontSize = 21.sp, lineHeight = 30.sp, modifier = modifier)
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = Palette.Text) {
    Text(text, color = color, fontSize = 25.sp, lineHeight = 36.sp, modifier = modifier)
}

@Composable
fun GapRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

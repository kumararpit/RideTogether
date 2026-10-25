package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.DividerColor
import com.example.ui.theme.RideGuidanceGreen
import com.example.ui.theme.SlateDark500
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.SlateDark600
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * The RideTogether design system.
 *
 * One place for the spacing scale, the surface treatment and the shared
 * controls, so every screen in the 14-screen reference is built from the same
 * geometry instead of local values.
 */
object RTSpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** Horizontal page padding used by every full screen. */
    val page: Dp = 20.dp
}

object RTShapes {
    val control = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(18.dp)
    val sheet = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(50)
}

/** The one primary action style (amber, 52dp tall, full width by default). */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    testTag: String? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        colors = ButtonDefaults.buttonColors(
            containerColor = AmberPrimary,
            contentColor = Color(0xFF1A1205),
            disabledContainerColor = SlateDark600,
            disabledContentColor = TextTertiary
        ),
        shape = RTShapes.control,
        contentPadding = PaddingValues(horizontal = RTSpacing.md)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color(0xFF1A1205),
                strokeWidth = 2.dp
            )
        } else if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(19.dp))
        }
        if (loading || icon != null) Spacer(modifier = Modifier.width(RTSpacing.xs))
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The secondary action style (outlined, same height as the primary). */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    testTag: String? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = TextPrimary,
            disabledContentColor = TextTertiary
        ),
        border = BorderStroke(1.dp, SlateDark500),
        shape = RTShapes.control,
        contentPadding = PaddingValues(horizontal = RTSpacing.sm)
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(RTSpacing.xs))
        }
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * The standard top bar: back affordance, a title with an optional subtitle and
 * trailing actions. It owns the status-bar inset so no screen has to.
 */
@Composable
fun RideTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = "Back",
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = RTSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = backContentDescription,
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(RTSpacing.xxs))
        } else {
            Spacer(modifier = Modifier.width(RTSpacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

/** The standard surface card: charcoal, hairline border, rounded corners. */
@Composable
fun RTCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(RTSpacing.md),
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tagModifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(tagModifier)
            .then(if (onClick != null) Modifier.clip(RTShapes.card).clickable(onClick = onClick) else Modifier),
        color = SlateDark800,
        shape = RTShapes.card,
        border = BorderStroke(1.dp, SlateDark500)
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** A section heading, optionally with a trailing text action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                color = AmberPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RTShapes.control)
                    .clickable(onClick = onAction)
                    .padding(horizontal = RTSpacing.xs, vertical = RTSpacing.xxs)
            )
        }
    }
}

/** A small state pill (Open, Offline, Leader, +3 min…). */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    testTag: String? = null
) {
    Surface(
        modifier = modifier.then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        color = color.copy(alpha = 0.16f),
        shape = RTShapes.pill
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/** A rider avatar with deterministic initials, the way the reference shows riders. */
@Composable
fun RiderAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    ringColor: Color? = null,
    badge: @Composable (() -> Unit)? = null
) {
    val palette = listOf(
        Color(0xFF3E6B8C), Color(0xFF7A5C8C), Color(0xFF4E7A5E),
        Color(0xFF8C6B4A), Color(0xFF5C6B8C), Color(0xFF8C4A5C)
    )
    val initial = name.trim().takeIf { it.isNotEmpty() }?.take(1)?.uppercase() ?: "R"
    val fill = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(fill)
                .then(
                    if (ringColor != null) Modifier.border(1.5.dp, ringColor, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                color = Color.White,
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (badge != null) badge()
    }
}

/** A neutral empty state: icon, title, one line, one optional action. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(RTSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(SlateDark700),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(RTSpacing.sm))
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(RTSpacing.xxs))
        Text(
            text = message,
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(RTSpacing.md))
            OutlinedButton(
                onClick = onAction,
                border = BorderStroke(1.dp, SlateDark500),
                shape = RTShapes.control
            ) {
                Text(text = actionLabel, color = TextPrimary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun LoadingState(message: String = "Loading…", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(RTSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = AmberPrimary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.height(RTSpacing.sm))
        Text(text = message, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(RTSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(RTSpacing.sm))
            OutlinedButton(
                onClick = onRetry,
                border = BorderStroke(1.dp, SlateDark500),
                shape = RTShapes.control
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(RTSpacing.xxs))
                Text(text = "Try again", color = TextPrimary, fontSize = 13.sp)
            }
        }
    }
}

/** A hairline divider used inside cards. */
@Composable
fun RTDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DividerColor)
    )
}

/** Icon + label + optional trailing value, used by route and place cards. */
@Composable
fun InfoRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = TextSecondary,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(SlateDark700),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(RTSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
    }
}

/** Inline validation error: red-tinted surface with a message and optional dismiss action. */
@Composable
fun InlineError(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RTSpacing.md, vertical = RTSpacing.sm)
            .background(StatusEmergencyRed.copy(alpha = 0.12f))
            .clip(RTShapes.control)
            .border(BorderStroke(1.dp, StatusEmergencyRed.copy(alpha = 0.4f)))
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(StatusEmergencyRed),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(modifier = Modifier.width(RTSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message,
                color = TextPrimary,
                fontSize = 13.sp,
                maxLines = 2
            )
        }
        if (onDismiss != null) {
            Spacer(modifier = Modifier.width(RTSpacing.xs))
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** Route metric row: icon, label, primary value, optional trailing subtitle. */
@Composable
fun RouteMetricRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(SlateDark700),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AmberPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(RTSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
        trailing?.invoke()
    }
}

/** Ride summary card: title, optional subtitle, and content. */
@Composable
fun RideSummaryCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    RTCard(
        modifier = modifier,
        contentPadding = PaddingValues(RTSpacing.md),
        testTag = "ride_summary_card"
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(RTSpacing.xxs))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
        Spacer(modifier = Modifier.height(RTSpacing.md))
        content()
    }
}

/** Navigation maneuver card: deep green surface per the design system spec. */
@Composable
fun NavigationManeuverCard(
    icon: ImageVector,
    instruction: String,
    distance: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RTSpacing.md, vertical = RTSpacing.sm)
            .background(RideGuidanceGreen)
            .clip(RTShapes.control)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(RideGuidanceGreen.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(RTSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = instruction,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2
            )
            Text(
                text = distance,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp
            )
        }
    }
}

/** Circular map control button with an icon and content description. */
@Composable
fun MapControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(onClick = onClick)
            .background(if (selected) AmberPrimary.copy(alpha = 0.2f) else SlateDark700)
            .clip(CircleShape)
            .border(if (selected) BorderStroke(1.5.dp, AmberPrimary) else BorderStroke(1.dp, SlateDark500))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) AmberPrimary else TextPrimary,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** Bottom action bar row: full width, navigation-bars padded, evenly spaced actions. */
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = RTSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(RTSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

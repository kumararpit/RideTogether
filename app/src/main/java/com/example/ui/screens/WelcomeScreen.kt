package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.db.CompletedRideEntity
import com.example.data.db.SavedPlaceEntity
import com.example.data.model.Ride
import com.example.data.model.RideStatus
import com.example.ui.components.PrimaryButton
import com.example.ui.components.RTCard
import com.example.ui.components.RTShapes
import com.example.ui.components.RTSpacing
import com.example.ui.components.RideDetailsDialog
import com.example.ui.components.RideHistorySheet
import com.example.ui.components.SavedPlacesSheet
import com.example.ui.components.SecondaryButton
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusChip
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.CustomIcons
import com.example.ui.theme.SlateDark500
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusRidingGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screens 1 and 2 of the reference: the splash / sign-in entry, and Home.
 *
 * A signed-out rider sees the splash (hero art, wordmark, tagline, "Get
 * Started" and a guest route). A signed-in rider — or one who chose to carry
 * on as a guest — sees Home: the upcoming ride, the four quick actions, real
 * discovery content and the account footer.
 */
@Composable
fun WelcomeScreen(
    currentName: String,
    onNameChange: (String) -> Unit,
    currentMotorcycle: String = "",
    onMotorcycleChange: (String) -> Unit = {},
    activeRide: Ride? = null,
    activeRiderCount: Int = 1,
    isCurrentUserInActiveRide: Boolean = true,
    onRejoinRideClick: () -> Unit = {},
    onCreateRideClick: () -> Unit,
    onJoinRideClick: () -> Unit,
    onSignInClick: () -> Unit = {},
    onSignOutClick: (() -> Unit)? = null,
    isSignedIn: Boolean = false,
    userEmail: String? = null,
    pastRides: List<CompletedRideEntity> = emptyList(),
    savedPlaces: List<SavedPlaceEntity> = emptyList(),
    onRemoveSavedPlace: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var guestMode by rememberSaveable { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showSavedPlacesSheet by remember { mutableStateOf(false) }
    var selectedHistoryRideForDetails by remember { mutableStateOf<CompletedRideEntity?>(null) }

    val hasActiveRide = activeRide != null &&
        activeRide.status != RideStatus.COMPLETED &&
        activeRide.status != RideStatus.CANCELLED

    if (!isSignedIn && !guestMode) {
        SplashContent(
            onGetStarted = onSignInClick,
            onContinueAsGuest = { guestMode = true },
            modifier = modifier
        )
        return
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("welcome_screen"),
        color = SlateDark900
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RTSpacing.page)
        ) {
            Spacer(modifier = Modifier.height(RTSpacing.xs))

            // ── Brand bar ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = RTSpacing.xxs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(size = 32.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "RideTogether",
                        color = TextPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.4).sp
                    )
                }

                if (isSignedIn) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SlateDark700)
                            .clickable { showProfileSheet = true }
                            .testTag("profile_avatar"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = currentName.ifBlank { "R" }.take(1).uppercase(),
                            color = AmberPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    TextButton(onClick = onSignInClick) {
                        Text("Sign in", color = AmberPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(RTSpacing.lg))

            // ── Upcoming ride ───────────────────────────────────────────────
            if (hasActiveRide) {
                ActiveRideCard(
                    ride = activeRide!!,
                    riderCount = activeRiderCount,
                    isCurrentUserInActiveRide = isCurrentUserInActiveRide,
                    riderName = currentName,
                    onRejoin = onRejoinRideClick
                )
            } else {
                NoRideCard(onCreateRide = onCreateRideClick)
            }

            Spacer(modifier = Modifier.height(RTSpacing.xl))

            // ── Quick actions ───────────────────────────────────────────────
            // When no ride is active, the "No ride planned" card above is the
            // dominant Create Ride CTA, so the Create Ride tile is omitted here
            // to avoid duplication (UX-015). When a ride is active, the tile
            // offers planning a new ride alongside the resume path.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasActiveRide) {
                    QuickAction(
                        label = "Create Ride",
                        icon = Icons.Default.Add,
                        accent = AmberPrimary,
                        onClick = onCreateRideClick,
                        testTag = "create_ride_button",
                        modifier = Modifier.weight(1f)
                    )
                }
                QuickAction(
                    label = "Join Ride",
                    icon = Icons.Default.GroupAdd,
                    accent = StatusRidingGreen,
                    onClick = onJoinRideClick,
                    testTag = "join_ride_button",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickAction(
                    label = "My Rides",
                    icon = Icons.Default.History,
                    accent = Color(0xFF6AA9FF),
                    onClick = { showHistorySheet = true },
                    testTag = "my_rides_button",
                    modifier = Modifier.weight(1f)
                )
                QuickAction(
                    label = "Saved Places",
                    icon = Icons.Default.BookmarkBorder,
                    accent = Color(0xFFC9A2FF),
                    onClick = { showSavedPlacesSheet = true },
                    testTag = "saved_places_button",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(RTSpacing.xl))

            // ── Recent routes ─────────────────────────────────────────────────
            // Personal ride/place history — distinct from public discovery below
            // (UX-008: separate personal history from Discover rides).
            SectionHeader(
                title = "Recent routes",
                actionLabel = if (pastRides.isNotEmpty() || savedPlaces.isNotEmpty()) "See all" else null,
                onAction = {
                    if (pastRides.isNotEmpty()) showHistorySheet = true else showSavedPlacesSheet = true
                }
            )
            Spacer(modifier = Modifier.height(RTSpacing.sm))

            val latestRide = pastRides.maxByOrNull { it.completedAtMs }
            val latestPlace = savedPlaces.firstOrNull()
            when {
                latestPlace != null -> DiscoverPlaceCard(
                    place = latestPlace,
                    onClick = { showSavedPlacesSheet = true }
                )
                latestRide != null -> DiscoverRideCard(
                    ride = latestRide,
                    onClick = { selectedHistoryRideForDetails = latestRide }
                )
                else -> RTCard {
                    Text(
                        text = "No recent routes",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Routes you ride and places you save will appear here.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(RTSpacing.xl))

            // ── Discover rides ────────────────────────────────────────────────
            // Public / group rides available to join. Personal history lives in
            // "Recent routes" above so the two mental models are not mixed (UX-008).
            SectionHeader(
                title = "Discover rides",
                actionLabel = null,
                onAction = {}
            )
            Spacer(modifier = Modifier.height(RTSpacing.sm))
            RTCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No group rides nearby",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Public rides will appear here when hosts share them.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(RTSpacing.xl))

            // ── Account footer ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = RTSpacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isSignedIn) StatusRidingGreen else TextTertiary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSignedIn) "Cloud sync active" else "Local mode",
                        color = if (isSignedIn) StatusRidingGreen else TextTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (isSignedIn) {
                    Text(
                        text = userEmail ?: currentName,
                        color = TextTertiary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }

        if (showProfileSheet) {
            ProfileSheet(
                currentName = currentName,
                currentMotorcycle = currentMotorcycle,
                onNameChange = onNameChange,
                onMotorcycleChange = onMotorcycleChange,
                isSignedIn = isSignedIn,
                userEmail = userEmail,
                onSignOut = {
                    showProfileSheet = false
                    guestMode = false
                    onSignOutClick?.invoke()
                },
                onDismiss = { showProfileSheet = false }
            )
        }

        if (showSavedPlacesSheet) {
            SavedPlacesSheet(
                places = savedPlaces,
                onRemove = onRemoveSavedPlace,
                onDismiss = { showSavedPlacesSheet = false }
            )
        }

        if (showHistorySheet) {
            RideHistorySheet(
                rides = pastRides,
                onDismiss = { showHistorySheet = false },
                upcomingRide = if (hasActiveRide) activeRide else null
            )
        }

        if (selectedHistoryRideForDetails != null) {
            RideDetailsDialog(
                ride = selectedHistoryRideForDetails!!,
                onDismiss = { selectedHistoryRideForDetails = null }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Splash
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SplashContent(
    onGetStarted: () -> Unit,
    onContinueAsGuest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SlateDark900)
            .testTag("splash_screen")
    ) {
        // Original RideTogether artwork (the app's own scenic vector), faded
        // into the product background rather than shown as a stock photo.
        Image(
            painter = painterResource(id = R.drawable.scenic_mountain_road),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .align(Alignment.TopCenter)
        )

        // Legibility scrim: dark at the top (status bar) and at the bottom
        // (where the wordmark and the actions live).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to SlateDark900.copy(alpha = 0.55f),
                        0.30f to Color.Transparent,
                        0.55f to SlateDark900.copy(alpha = 0.72f),
                        0.72f to SlateDark900,
                        1f to SlateDark900
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.weight(1f))

            LogoMark(size = 64.dp)
            Spacer(modifier = Modifier.height(RTSpacing.md))
            Text(
                text = "RideTogether",
                color = TextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Ride Together. Go Further.",
                color = TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(RTSpacing.xxl))

            PrimaryButton(
                text = "Get Started",
                onClick = onGetStarted,
                testTag = "get_started_button"
            )
            Spacer(modifier = Modifier.height(RTSpacing.sm))
            SecondaryButton(
                text = "Continue as guest",
                onClick = onContinueAsGuest,
                testTag = "continue_as_guest_button"
            )

            Spacer(modifier = Modifier.height(RTSpacing.lg))
        }
    }
}

/** The RideTogether mark: an amber disc with the app's own motorcycle glyph. */
@Composable
fun LogoMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(AmberPrimary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = CustomIcons.MotorcycleLogo,
            contentDescription = "RideTogether",
            tint = Color(0xFF1A1205),
            modifier = Modifier.size(size * 0.62f)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Home pieces
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActiveRideCard(
    ride: Ride,
    riderCount: Int,
    isCurrentUserInActiveRide: Boolean,
    riderName: String,
    onRejoin: () -> Unit
) {
    RTCard(
        testTag = "active_ride_card",
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(
                text = if (ride.status == RideStatus.ACTIVE) "LIVE NOW" else "UPCOMING RIDE",
                color = if (ride.status == RideStatus.ACTIVE) StatusRidingGreen else AmberPrimary
            )
            Spacer(modifier = Modifier.weight(1f))
            ButtonCircle(
                icon = Icons.Default.KeyboardArrowRight,
                onClick = onRejoin,
                testTag = "rejoin_ride_button",
                contentDescription = if (isCurrentUserInActiveRide) "Resume ride" else "Rejoin ride"
            )
        }
        Spacer(modifier = Modifier.height(RTSpacing.sm))
        Text(
            text = ride.name,
            color = TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatRideStamp(ride.createdAtMs),
            color = TextSecondary,
            fontSize = 12.sp
        )
        // The auto-generated ride name is already the route, so the row would
        // repeat it verbatim on a brand-new ride.
        val routeLabel = "${ride.startLocationName} → ${ride.destinationName}"
        if (routeLabel != ride.name) {
            Spacer(modifier = Modifier.height(RTSpacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    tint = AmberPrimary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = routeLabel,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.height(RTSpacing.sm))
        // Real riders only: the current rider, then a truthful count.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(SlateDark700),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = riderName.ifBlank { "R" }.take(1).uppercase(),
                    color = AmberPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    riderCount <= 1 -> "You"
                    isCurrentUserInActiveRide -> "You + ${riderCount - 1} rider${if (riderCount == 2) "" else "s"}"
                    else -> "$riderCount riders"
                },
                color = TextTertiary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun NoRideCard(onCreateRide: () -> Unit) {
    RTCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(text = "PLAN A RIDE", color = AmberPrimary)
            Spacer(modifier = Modifier.weight(1f))
            ButtonCircle(
                icon = Icons.Default.Add,
                onClick = onCreateRide,
                contentDescription = "Create a ride"
            )
        }
        Spacer(modifier = Modifier.height(RTSpacing.sm))
        Text(
            text = "No ride planned",
            color = TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Pick a destination, invite your friends and ride together.",
            color = TextSecondary,
            fontSize = 13.sp
        )
    }
}

/** The circular amber action used on the ride card and in the reference. */
@Composable
private fun ButtonCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    testTag: String? = null
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(AmberPrimary)
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color(0xFF1A1205),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(96.dp)
            .testTag(testTag)
            .clip(RTShapes.card)
            .clickable(onClick = onClick),
        color = SlateDark800,
        shape = RTShapes.card,
        border = BorderStroke(1.dp, SlateDark500)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DiscoverPlaceCard(place: SavedPlaceEntity, onClick: () -> Unit) {
    RTCard(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AmberPrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BookmarkBorder,
                    contentDescription = null,
                    tint = AmberPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = place.name ?: "Saved place",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Saved place · ${place.type.lowercase().replace('_', ' ')}",
                    color = TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun DiscoverRideCard(ride: CompletedRideEntity, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RTShapes.card)
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(id = R.drawable.scenic_mountain_road),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to SlateDark900.copy(alpha = 0.35f),
                        1f to SlateDark900.copy(alpha = 0.92f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
        ) {
            Text(
                text = "YOUR RECENT ROUTE",
                color = AmberPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${ride.startLocationName} → ${ride.destinationName}",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${ride.formattedDistance} · ${formatRideStamp(ride.completedAtMs)}",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Profile sheet
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(
    currentName: String,
    currentMotorcycle: String,
    onNameChange: (String) -> Unit,
    onMotorcycleChange: (String) -> Unit,
    isSignedIn: Boolean,
    userEmail: String?,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    var nameInput by remember { mutableStateOf(currentName) }
    var bikeInput by remember { mutableStateOf(currentMotorcycle) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SlateDark800) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AmberPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = currentName.ifBlank { "R" }.take(1).uppercase(),
                        color = Color(0xFF1A1205),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = currentName.ifBlank { "Rider" },
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isSignedIn) (userEmail ?: "Signed in") else "Guest · local mode",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(RTSpacing.lg))
            Text("RIDER NAME", color = AmberPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it; onNameChange(it) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Person, contentDescription = null, tint = AmberPrimary)
                },
                singleLine = true,
                colors = profileFieldColors(),
                shape = RTShapes.control,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rider_name_input")
            )

            Spacer(modifier = Modifier.height(RTSpacing.md))
            Text("MOTORCYCLE MODEL (OPTIONAL)", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = bikeInput,
                onValueChange = { bikeInput = it; onMotorcycleChange(it) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.TwoWheeler, contentDescription = null, tint = AmberPrimary)
                },
                placeholder = { Text("e.g. Triumph Tiger 900", color = TextTertiary) },
                singleLine = true,
                colors = profileFieldColors(),
                shape = RTShapes.control,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("motorcycle_model_input")
            )

            Spacer(modifier = Modifier.height(RTSpacing.lg))
            PrimaryButton(text = "Done", onClick = onDismiss, testTag = "edit_profile_button")

            AnimatedVisibility(visible = isSignedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = onSignOut) {
                        Text("Sign out", color = com.example.ui.theme.StatusEmergencyRed, fontSize = 13.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(RTSpacing.lg))
        }
    }
}

@Composable
private fun profileFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AmberPrimary,
    unfocusedBorderColor = SlateDark500,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = AmberPrimary
)

// ─────────────────────────────────────────────────────────────────────────────
// Formatting
// ─────────────────────────────────────────────────────────────────────────────

private fun formatRideStamp(atMs: Long): String =
    SimpleDateFormat("EEE, d MMM · h:mm a", Locale.getDefault()).format(Date(atMs))

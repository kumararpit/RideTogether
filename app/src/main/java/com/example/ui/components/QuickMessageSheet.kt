package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.QuickMessageType
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.StatusEmergencyRed
import com.example.ui.theme.SurfaceCardElevated

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickMessageSheet(
    onSendMessage: (QuickMessageType) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Lightweight confirmation for the routine "Need help" broadcast — distinct
    // from SOS which uses a 5-second emergency countdown (see SosConfirmDialog).
    var confirmNeedHelp by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SlateDark900,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Gray.copy(alpha = 0.5f))
            )
        },
        modifier = Modifier.testTag("quick_message_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Send to Group",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "One-tap messages for riding with gloves",
                        color = Color(0xFF90A4AE),
                        fontSize = 13.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_quick_message_sheet")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Categorized Quick Messages: STATUS vs COORDINATION vs ATTENTION
            val statusMessages = listOf(
                QuickMessageType.IM_OKAY,
                QuickMessageType.STOPPED,
                QuickMessageType.REGROUP,
                QuickMessageType.NEED_FUEL
            )

            val coordinationMessages = listOf(
                QuickMessageType.WAIT_FOR_ME,
                QuickMessageType.SLOW_DOWN,
                QuickMessageType.FOOD_STOP
            )

            if (confirmNeedHelp) {
                // Lightweight confirmation for the routine "Need help" broadcast.
                // Distinct from SOS: no countdown, no emergency red, plain "Send"
                // action — the user explicitly opts in to notifying the group.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Help,
                        contentDescription = "Need help",
                        tint = AmberPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Send 'Need help' to group?",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Notifies all riders you need assistance — this is a routine broadcast, not an emergency SOS.",
                        color = Color(0xFF90A4AE),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { confirmNeedHelp = false },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF90A4AE)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Cancel",
                                color = Color(0xFF90A4AE),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Button(
                            onClick = {
                                confirmNeedHelp = false
                                onSendMessage(QuickMessageType.NEED_HELP)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("send_need_help_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmberPrimary,
                                contentColor = Color(0xFF1A1205)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Send Need Help",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "QUICK STATUS",
                        color = AmberPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    statusMessages.forEach { option ->
                        QuickMessageTile(option = option, onClick = { onSendMessage(option) })
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "COORDINATION",
                        color = AmberPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    coordinationMessages.forEach { option ->
                        QuickMessageTile(option = option, onClick = { onSendMessage(option) })
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // UX-010: "Need help" is a routine attention broadcast, visually
                    // and semantically separate from the emergency SOS flow (red,
                    // 5-second countdown, "SEND SOS NOW"). It uses an amber tile and
                    // an explicit lightweight confirmation instead.
                    Text(
                        text = "ATTENTION",
                        color = AmberPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    QuickMessageTile(
                        option = QuickMessageType.NEED_HELP,
                        onClick = { confirmNeedHelp = true },
                        attention = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun QuickMessageTile(
    option: QuickMessageType,
    onClick: () -> Unit,
    attention: Boolean = false
) {
    val tileColor = if (attention) AmberPrimary.copy(alpha = 0.18f) else SurfaceCardElevated
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .testTag("quick_msg_btn_${option.name}"),
        color = tileColor,
        tonalElevation = 2.dp,
        border = if (attention) BorderStroke(1.dp, AmberPrimary.copy(alpha = 0.5f)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = option.icon,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = option.label,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

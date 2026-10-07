package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.SlateDark700
import com.example.ui.theme.SlateDark800
import com.example.ui.theme.SlateDark900
import com.example.ui.theme.SurfaceCard

@Composable
fun CreateRideScreen(
    onBack: () -> Unit,
    onCreateRide: (name: String, start: String, destination: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var rideName by remember { mutableStateOf("Pune → Lonavala") }
    var startLocation by remember { mutableStateOf("Current Location (Pune)") }
    var destination by remember { mutableStateOf("Lonavala") }
    var nameError by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("create_ride_screen"),
        color = SlateDark900
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                // Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("back_from_create_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Create Group Ride",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Ride Name Field
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "RIDE NAME *",
                            color = AmberPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = rideName,
                            onValueChange = {
                                rideName = it
                                nameError = it.isBlank()
                            },
                            placeholder = { Text("e.g. Pune → Lonavala", color = Color(0xFF78909C)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.TwoWheeler,
                                    contentDescription = null,
                                    tint = AmberPrimary
                                )
                            },
                            isError = nameError,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AmberPrimary,
                                unfocusedBorderColor = SlateDark700,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ride_name_input"),
                            shape = RoundedCornerShape(12.dp)
                        )
                        if (nameError) {
                            Text(
                                text = "Ride name is required",
                                color = Color(0xFFFF5252),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Start Location Field
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "START POINT",
                            color = AmberPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = startLocation,
                            onValueChange = { startLocation = it },
                            placeholder = { Text("Current Location", color = Color(0xFF78909C)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.MyLocation,
                                    contentDescription = null,
                                    tint = Color(0xFF00E676)
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AmberPrimary,
                                unfocusedBorderColor = SlateDark700,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("start_location_input"),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Destination Field
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "DESTINATION",
                            color = AmberPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = destination,
                            onValueChange = { destination = it },
                            placeholder = { Text("Destination town or landmark", color = Color(0xFF78909C)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Flag,
                                    contentDescription = null,
                                    tint = AmberPrimary
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AmberPrimary,
                                unfocusedBorderColor = SlateDark700,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("destination_input"),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Info note
                Text(
                    text = "A unique 6-character ride code (e.g. ABC123) will be generated for your pack to join.",
                    color = Color(0xFF90A4AE),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }

            // Create Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
            ) {
                Button(
                    onClick = {
                        if (rideName.isBlank()) {
                            nameError = true
                        } else {
                            onCreateRide(rideName, startLocation, destination)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("submit_create_ride_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberPrimary,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "Create Ride & Open Lobby",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

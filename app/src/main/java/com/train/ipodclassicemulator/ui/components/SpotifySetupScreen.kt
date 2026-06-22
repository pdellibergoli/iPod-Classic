package com.train.ipodclassicemulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySetupScreen(
    onCredentialsSaved: (String, String) -> Unit
) {
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    // Stato per mostrare/nascondere la guida dettagliata
    var isHelpExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F7)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                // Permette alla schermata di scorrere se la tastiera o la guida occupano spazio
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "iPod Emulator Setup",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Text(
                text = "Inserisci le tue credenziali Spotify Developer per abilitare la riproduzione musicale.",
                fontSize = 13.sp,
                color = Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // 🟢 CARD DI AIUTO / GUIDA PASSO PASSO
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8E8ED)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "❓ Come ottenere Client ID e Secret?",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1C1C1E)
                        )
                        TextButton(onClick = { isHelpExpanded = !isHelpExpanded }) {
                            Text(if (isHelpExpanded) "Nascondi" else "Mostra Guida", fontSize = 12.sp, color = Color(0xFF007AFF))
                        }
                    }

                    if (isHelpExpanded) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "1. Vai su: developer.spotify.com e fai il login.\n" +
                                    "2. Accedi alla \"Dashboard\" e clicca su \"Create App\".\n" +
                                    "3. Compila i campi richiesti. Nel campo Redirect URI inserisci tassativamente:\n" +
                                    "   ipodapp://spotify-callback\n" +
                                    "4. Seleziona \"Web API\" e \"Android\" nelle impostazioni delle API richieste.\n" +
                                    "5. Salva, apri i \"Settings\" della tua nuova app e copia il Client ID e il Client Secret qui sotto.",
                            fontSize = 12.sp,
                            color = Color(0xFF3A3A3C),
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Campo Client ID
            OutlinedTextField(
                value = clientId,
                onValueChange = {
                    clientId = it
                    showError = false
                },
                label = { Text("Spotify Client ID") },
                placeholder = { Text("Incolla il tuo Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            // Campo Client Secret
            OutlinedTextField(
                value = clientSecret,
                onValueChange = {
                    clientSecret = it
                    showError = false
                },
                label = { Text("Spotify Client Secret") },
                placeholder = { Text("Incolla il tuo Client Secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            if (showError) {
                Text(
                    text = "Entrambi i campi sono obbligatori!",
                    color = Color.Red,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Pulsante di Conferma
            Button(
                onClick = {
                    if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
                        onCredentialsSaved(clientId.trim(), clientSecret.trim())
                    } else {
                        showError = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
            ) {
                Text(
                    text = "Salva e Avvia iPod",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
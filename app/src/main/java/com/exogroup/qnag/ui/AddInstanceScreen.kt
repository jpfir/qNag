package com.exogroup.qnag.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.NagiosInstance

@Composable
fun AddInstanceScreen(
    onSave: (NagiosInstance) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Add Nagios Instance", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it; validationError = null },
            label = { Text("Display Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; validationError = null },
            label = { Text("Nagios URL (e.g. https://nagios.example.com)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; validationError = null },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; validationError = null },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        validationError?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val err = validateFields(name, url, username, password)
                if (err != null) {
                    validationError = err
                } else {
                    validationError = null
                    onSave(
                        NagiosInstance(
                            name = name.trim(),
                            url = url.trim(),
                            username = username.trim(),
                            // Do not trim password — spaces may be intentional
                            password = password,
                        )
                    )
                }
            }
        ) {
            Text("Save Securely")
        }

        if (onCancel != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

private fun validateFields(name: String, url: String, username: String, password: String): String? {
    if (name.isBlank()) return "Display name is required."
    if (url.isBlank() || url == "https://") return "A valid Nagios URL is required."
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        return "URL must start with http:// or https://"
    }
    if (username.isBlank()) return "Username is required."
    if (password.isEmpty()) return "Password is required."
    return null
}

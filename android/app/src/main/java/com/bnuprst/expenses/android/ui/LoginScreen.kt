package com.bnuprst.expenses.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bnuprst.expenses.android.AppViewModel
import com.bnuprst.expenses.android.R
import com.bnuprst.expenses.android.UiState

@Composable
fun LoginScreen(state: UiState, vm: AppViewModel) {
    var server by rememberSaveable { mutableStateOf(state.serverUrl.ifEmpty { "https://" }) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val canSubmit = !state.loading && email.isNotBlank() && password.isNotBlank() && (!register || name.isNotBlank())
    val submit = { if (canSubmit) vm.authenticate(server, email, password, name, register) }

    Scaffold(snackbarHost = { SnackbarHost(LocalSnackbar.current) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(painterResource(R.drawable.ic_wallet), null, Modifier.padding(20.dp).size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(if (register) "Create account" else "Welcome back", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (register) "Track family spending together" else "Sign in to your expenses server",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val fieldModifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)
            OutlinedTextField(
                server, { server = it }, label = { Text("Server URL") }, singleLine = true, modifier = fieldModifier,
                leadingIcon = { Icon(painterResource(R.drawable.ic_account_balance), null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            if (register) OutlinedTextField(
                name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = fieldModifier,
                leadingIcon = { Icon(Icons.Default.Person, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = fieldModifier,
                leadingIcon = { Icon(Icons.Default.Email, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = fieldModifier,
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            painterResource(if (showPassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = submit, enabled = canSubmit, modifier = fieldModifier.height(48.dp)) {
                Text(if (register) "Create account" else "Sign in")
            }
            TextButton(onClick = { register = !register }) { Text(if (register) "Have an account? Sign in" else "New here? Create an account") }
        }
    }
}

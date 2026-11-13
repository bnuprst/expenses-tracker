package com.bnuprst.expenses.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
    var language by rememberSaveable { mutableStateOf(false) }
    val canSubmit = !state.loading && email.isNotBlank() && password.isNotBlank() && (!register || name.isNotBlank())
    val submit = { if (canSubmit) vm.authenticate(server, email, password, name, register) }

    Scaffold(snackbarHost = { SnackbarHost(LocalSnackbar.current) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dims.ItemGap),
        ) {
            Spacer(Modifier.height(Dims.SectionGap))
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(painterResource(R.drawable.ic_wallet), null, Modifier.padding(20.dp).size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(if (register) R.string.login_title_create else R.string.login_title_welcome), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(if (register) R.string.login_subtitle_create else R.string.login_subtitle_signin),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dims.ItemGap))
            val fieldModifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
            OutlinedTextField(
                server, { server = it }, label = { Text(stringResource(R.string.field_server_url)) }, singleLine = true, modifier = fieldModifier,
                leadingIcon = { Icon(painterResource(R.drawable.ic_account_balance), null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            if (register) OutlinedTextField(
                name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true, modifier = fieldModifier,
                leadingIcon = { Icon(Icons.Default.Person, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                email, { email = it }, label = { Text(stringResource(R.string.field_email)) }, singleLine = true, modifier = fieldModifier,
                leadingIcon = { Icon(Icons.Default.Email, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                password, { password = it }, label = { Text(stringResource(R.string.field_password)) }, singleLine = true, modifier = fieldModifier,
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            painterResource(if (showPassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            stringResource(if (showPassword) R.string.action_hide_password else R.string.action_show_password),
                        )
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = submit, enabled = canSubmit, modifier = fieldModifier.defaultMinSize(minHeight = Dims.Control)) {
                Text(stringResource(if (register) R.string.login_title_create else R.string.action_sign_in), maxLines = 1)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onClick = { register = !register }) {
                    Text(stringResource(if (register) R.string.login_switch_to_signin else R.string.login_switch_to_register), maxLines = 2, textAlign = TextAlign.Center)
                }
                TextButton(onClick = { language = true }) {
                    Text(stringResource(R.string.action_language), maxLines = 2, textAlign = TextAlign.Center)
                }
            }
            if (language) LanguageDialog { language = false }
        }
    }
}

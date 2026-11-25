package com.pocketfinancer.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pocketfinancer.ui.theme.M3_Background
import com.pocketfinancer.ui.theme.M3_OnPrimary
import com.pocketfinancer.ui.theme.M3_OnSurface
import com.pocketfinancer.ui.theme.M3_OnSurfaceVariant
import com.pocketfinancer.ui.theme.M3_OutlineVariant
import com.pocketfinancer.ui.theme.M3_Primary
import com.pocketfinancer.ui.theme.M3_PrimaryContainer
import com.pocketfinancer.ui.theme.M3_SurfaceContainerLow
import com.pocketfinancer.data.repository.ProcessingConfigurationRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.COMPLETED) onComplete()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val readGranted =
            result[Manifest.permission.READ_SMS] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_SMS
                ) == PackageManager.PERMISSION_GRANTED
        val receiveGranted =
            result[Manifest.permission.RECEIVE_SMS] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionResult(readGranted && receiveGranted)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(M3_Background)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn().togetherWith(fadeOut()) },
            label = "onboarding_step"
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> CalmIntroduction(
                    onContinue = {
                        viewModel.setStep(OnboardingStep.PRIMARY_CURRENCY)
                    }
                )

                OnboardingStep.PRIMARY_CURRENCY -> PrimaryCurrencyStep(
                    selectedCurrency = state.selectedPrimaryCurrency,
                    onCurrencySelected = viewModel::selectPrimaryCurrency,
                    onConfirm = viewModel::confirmPrimaryCurrency
                )

                OnboardingStep.PERMISSIONS -> SmsPermissionStep(
                    deniedCount = state.deniedCount,
                    onRequestPermission = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_SMS,
                                Manifest.permission.RECEIVE_SMS
                            )
                        )
                    },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    permanentlyDenied = smsPermissionsPermanentlyDenied(
                        context = context,
                        deniedCount = state.deniedCount
                    )
                )

                // Model work no longer belongs before the app shell. These
                // legacy enum values remain for the existing background
                // manager/model-upgrade state machine.
                OnboardingStep.DOWNLOAD_SLM,
                OnboardingStep.SYNCING,
                OnboardingStep.COMPLETED -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrimaryCurrencyStep(
    selectedCurrency: String,
    onCurrencySelected: (String) -> Unit,
    onConfirm: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = M3_Primary,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Choose your primary currency",
            color = M3_OnSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "This is used only when an alert has no explicit currency. Every alert keeps an immutable snapshot of this choice.",
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selectedCurrency,
                onValueChange = {},
                readOnly = true,
                label = { Text("Primary currency") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                ProcessingConfigurationRepository.SUPPORTED.sorted().forEach { currency ->
                    DropdownMenuItem(
                        text = { Text(currency) },
                        onClick = {
                            onCurrencySelected(currency)
                            expanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = M3_Primary)
        ) {
            Text("Confirm $selectedCurrency", color = M3_OnPrimary)
        }
    }
}

@Composable
private fun CalmIntroduction(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(M3_PrimaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = M3_Primary,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "A clear ledger from the alerts you already receive",
            color = M3_OnSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Pocket Financer reads eligible bank and payment SMS on this device, explains what it found, and keeps saved transaction evidence encrypted locally.",
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = M3_Primary)
        ) {
            Text("Continue", color = M3_OnPrimary)
            Spacer(Modifier.size(8.dp))
            Icon(
                imageVector = Icons.Rounded.ArrowForward,
                contentDescription = null,
                tint = M3_OnPrimary
            )
        }
    }
}

@Composable
private fun SmsPermissionStep(
    deniedCount: Int,
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.MarkEmailRead,
            contentDescription = null,
            tint = M3_Primary,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "SMS access is required",
            color = M3_OnSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "It lets Pocket Financer discover transaction alerts and capture new eligible alerts. Personal, OTP, and promotional messages are filtered before local AI processing.",
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = M3_SurfaceContainerLow
            ),
            border = BorderStroke(
                1.dp,
                M3_OutlineVariant.copy(alpha = 0.35f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = if (deniedCount == 0) {
                    "The app shell opens as soon as SMS access is granted. Model preparation and history import happen later, only after you confirm them."
                } else {
                    "Nothing was downloaded. Grant SMS access when you are ready to continue."
                },
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
        Spacer(Modifier.height(24.dp))

        if (permanentlyDenied) {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Open app settings")
            }
        } else {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text(if (deniedCount == 0) "Allow SMS access" else "Try again")
            }
        }

        if (permanentlyDenied) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Check permission again")
            }
        }
    }
}

internal fun backgroundWorkNoticeText(hasNotificationPermission: Boolean): String =
    if (hasNotificationPermission) {
        "You can switch apps and track progress in notifications."
    } else {
        "You can switch apps and return to Pocket Financer to check progress."
    }

private fun smsPermissionsPermanentlyDenied(
    context: Context,
    deniedCount: Int
): Boolean {
    if (deniedCount == 0) return false
    val activity = context.findActivity() ?: return false
    val readDenied = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_SMS
    ) != PackageManager.PERMISSION_GRANTED
    val receiveDenied = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECEIVE_SMS
    ) != PackageManager.PERMISSION_GRANTED
    return (readDenied &&
        !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.READ_SMS
        )) ||
        (receiveDenied &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECEIVE_SMS
            ))
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

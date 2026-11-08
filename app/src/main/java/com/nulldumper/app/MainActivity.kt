package com.nulldumper.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private val Night = Color(0xFF080B16)
private val Panel = Color(0xFF11182A)
private val PanelRaised = Color(0xFF172238)
private val Cyan = Color(0xFF38DDF2)
private val Purple = Color(0xFFB48AFF)
private val Muted = Color(0xFF99A8C0)
private val Good = Color(0xFF59D99A)
private val Warning = Color(0xFFFFC66D)
private val Bad = Color(0xFFFF7F91)

private data class ScanSummary(
    val hasIl2Cpp: Boolean,
    val hasMetadata: Boolean,
    val abis: Set<String>,
    val scanned: Boolean = true,
) {
    val ready: Boolean get() = scanned && hasIl2Cpp && hasMetadata
}

private enum class InputMode(val title: String) {
    INSTALLED_APP("Installed app"),
    APK_FILES("APK file(s)"),
    MANUAL_PAIR("Manual pair"),
}

private data class DumpOptions(
    val codm: Boolean = false,
    val forceDump: Boolean = false,
    val dumpBase: String = "",
    val codeRegistration: String = "",
    val metadataRegistration: String = "",
)

private data class AppSeed(
    val label: String,
    val packageName: String,
    val version: String,
    val apkPaths: List<File>,
)

private data class DeviceApp(
    val seed: AppSeed,
    val scan: ScanSummary,
)

private data class DumpSource(
    val label: String,
    val packageName: String?,
    val apkPaths: List<File>,
    val scan: ScanSummary,
    val binaryFile: File? = null,
    val metadataFile: File? = null,
    val options: DumpOptions = DumpOptions(),
) {
    val isManualPair: Boolean get() = binaryFile != null && metadataFile != null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NullDumperApp() }
    }
}

@Composable
private fun NullDumperTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Cyan,
        secondary = Purple,
        background = Night,
        surface = Panel,
        onPrimary = Night,
        onSecondary = Night,
        onBackground = Color(0xFFF3F6FD),
        onSurface = Color(0xFFF3F6FD),
        error = Bad,
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NullDumperApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installedApps by remember { mutableStateOf<List<DeviceApp>>(emptyList()) }
    var inputMode by remember { mutableStateOf(InputMode.INSTALLED_APP) }
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<DumpSource?>(null) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var scanningPackage by remember { mutableStateOf<String?>(null) }
    var scanProgress by remember { mutableStateOf("Loading user-installed apps…") }
    var manualBinaryFile by remember { mutableStateOf<File?>(null) }
    var manualBinaryLabel by remember { mutableStateOf("") }
    var manualMetadataFile by remember { mutableStateOf<File?>(null) }
    var manualMetadataLabel by remember { mutableStateOf("") }
    var manualCodm by remember { mutableStateOf(false) }
    var manualForceDump by remember { mutableStateOf(false) }
    var manualDumpBase by remember { mutableStateOf("") }
    var manualCodeRegistration by remember { mutableStateOf("") }
    var manualMetadataRegistration by remember { mutableStateOf("") }
    var lowMemory by remember { mutableStateOf(true) }
    var isDumping by remember { mutableStateOf(false) }
    var currentStage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var outputFolder by remember { mutableStateOf<File?>(null) }
    var activeProcess by remember { mutableStateOf<Process?>(null) }
    val logLines = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        isLoadingApps = true
        val seeds = withContext(Dispatchers.IO) { installedAppSeeds(context) }
        // Only read package labels and APK paths until the user taps an app.
        // APK archives are inspected one-at-a-time on explicit selection.
        installedApps = seeds.map { seed ->
            DeviceApp(seed, ScanSummary(false, false, emptySet(), scanned = false))
        }
        isLoadingApps = false
        scanProgress = "${seeds.size} user-installed app(s) · select one to inspect its APKs"
    }

    val apkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                errorMessage = null
                outputFolder = null
                currentStage = "Importing selected APKs…"
                try {
                    val imported = withContext(Dispatchers.IO) { copySelectedApks(context, uris) }
                    val scan = withContext(Dispatchers.IO) { inspectApks(imported) }
                    selectedSource = DumpSource(
                        label = if (imported.size == 1) imported.first().name else "${imported.size} APK split(s)",
                        packageName = null,
                        apkPaths = imported,
                        scan = scan,
                    )
                    currentStage = "APK scan complete"
                } catch (error: Exception) {
                    currentStage = ""
                    errorMessage = error.localizedMessage ?: "Could not import those APK files."
                }
            }
        }
    }

    val updateManualSource: () -> Unit = {
        val binary = manualBinaryFile
        val metadata = manualMetadataFile
        selectedSource = if (binary != null && metadata != null) {
            DumpSource(
                label = "${manualBinaryLabel.ifBlank { binary.name }} + ${manualMetadataLabel.ifBlank { metadata.name }}",
                packageName = null,
                apkPaths = emptyList(),
                scan = ScanSummary(true, true, emptySet()),
                binaryFile = binary,
                metadataFile = metadata,
                options = DumpOptions(
                    codm = manualCodm,
                    forceDump = manualForceDump,
                    dumpBase = manualDumpBase,
                    codeRegistration = manualCodeRegistration,
                    metadataRegistration = manualMetadataRegistration,
                ),
            )
        } else {
            null
        }
    }

    val binaryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { copyPickedFile(context, uri, "manual-binary") }
                manualBinaryFile = file
                manualBinaryLabel = file.name
                updateManualSource()
                errorMessage = null
                currentStage = "Native binary selected"
            } catch (error: Exception) {
                errorMessage = error.localizedMessage ?: "Could not import the native binary."
            }
        }
    }

    val metadataPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { copyPickedFile(context, uri, "manual-metadata") }
                manualMetadataFile = file
                manualMetadataLabel = file.name
                updateManualSource()
                errorMessage = null
                currentStage = "Metadata file selected"
            } catch (error: Exception) {
                errorMessage = error.localizedMessage ?: "Could not import the metadata file."
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { destination ->
        val source = outputFolder
        if (destination != null && source != null) {
            scope.launch {
                currentStage = "Preparing export…"
                try {
                    withContext(Dispatchers.IO) { zipDump(context, source, destination) }
                    currentStage = "Export saved"
                } catch (error: Exception) {
                    errorMessage = error.localizedMessage ?: "Could not export the dump."
                    currentStage = ""
                }
            }
        }
    }

    val visibleApps = remember(installedApps, query) {
        installedApps.filter {
            query.isBlank() || it.seed.label.contains(query, ignoreCase = true) ||
                it.seed.packageName.contains(query, ignoreCase = true)
        }
    }

    NullDumperTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Night) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.app_icon),
                            contentDescription = "Null Dumper icon",
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("NULL DUMPER", fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.7.sp)
                            Text("IL2CPP · APK scanner", color = Muted, fontSize = 12.sp)
                        }
                        Text("V1", color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                item { HeroCard(installedApps.size, isLoadingApps) }

                item {
                    InputModePicker(
                        selected = inputMode,
                        enabled = !isDumping && scanningPackage == null,
                        onSelected = { mode ->
                            inputMode = mode
                            selectedSource = null
                            if (mode == InputMode.MANUAL_PAIR) updateManualSource()
                            outputFolder = null
                            errorMessage = null
                            currentStage = ""
                        },
                    )
                }

                when (inputMode) {
                    InputMode.INSTALLED_APP -> {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("USER-INSTALLED APPS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                                    Text(if (isLoadingApps) scanProgress else "${visibleApps.size} app(s)", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                if (isLoadingApps || scanningPackage != null) CircularProgressIndicator(Modifier.size(19.dp), color = Cyan, strokeWidth = 2.dp)
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("Search name or package id", color = Muted) },
                                leadingIcon = { Text("⌕", color = Cyan, fontSize = 22.sp) },
                                shape = RoundedCornerShape(14.dp),
                            )
                        }
                        if (isLoadingApps && visibleApps.isEmpty()) {
                            item { Text(scanProgress, color = Muted, fontSize = 12.sp) }
                        }
                        items(visibleApps, key = { it.seed.packageName }) { app ->
                            InstalledAppCard(
                                app = app,
                                selected = selectedSource?.packageName == app.seed.packageName,
                                scanning = scanningPackage == app.seed.packageName,
                                onClick = {
                                    if (scanningPackage == null) {
                                        selectedSource = null
                                        outputFolder = null
                                        scope.launch {
                                            scanningPackage = app.seed.packageName
                                            scanProgress = "Inspecting ${app.seed.label} APKs…"
                                            try {
                                                val inspected = withContext(Dispatchers.IO) { inspectApp(app.seed) }
                                                installedApps = installedApps.map {
                                                    if (it.seed.packageName == app.seed.packageName) inspected else it
                                                }
                                                selectedSource = DumpSource(app.seed.label, app.seed.packageName, app.seed.apkPaths, inspected.scan)
                                                errorMessage = null
                                                outputFolder = null
                                                currentStage = "Inspected ${app.seed.apkPaths.size} installed APK/split file(s)"
                                            } catch (error: Exception) {
                                                errorMessage = error.localizedMessage ?: "Could not inspect this app's APK files."
                                            } finally {
                                                scanningPackage = null
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                    InputMode.APK_FILES -> item {
                        ApkFilePickerCard(enabled = !isDumping, onChoose = { apkPicker.launch(arrayOf("*/*")) })
                    }
                    InputMode.MANUAL_PAIR -> item {
                        ManualPairPickerCard(
                            binaryName = manualBinaryLabel,
                            metadataName = manualMetadataLabel,
                            codm = manualCodm,
                            onCodmChange = { manualCodm = it; updateManualSource() },
                            forceDump = manualForceDump,
                            onForceDumpChange = { manualForceDump = it; updateManualSource() },
                            dumpBase = manualDumpBase,
                            onDumpBaseChange = { manualDumpBase = it; updateManualSource() },
                            codeRegistration = manualCodeRegistration,
                            onCodeRegistrationChange = { manualCodeRegistration = it; updateManualSource() },
                            metadataRegistration = manualMetadataRegistration,
                            onMetadataRegistrationChange = { manualMetadataRegistration = it; updateManualSource() },
                            enabled = !isDumping,
                            onChooseBinary = { binaryPicker.launch(arrayOf("*/*")) },
                            onChooseMetadata = { metadataPicker.launch(arrayOf("*/*")) },
                        )
                    }
                }

                if (selectedSource != null) {
                    item {
                        SelectedSourceCard(
                            source = selectedSource!!,
                            lowMemory = lowMemory,
                            onLowMemoryChange = { lowMemory = it },
                            isDumping = isDumping,
                            onClear = {
                                selectedSource = null
                                outputFolder = null
                                errorMessage = null
                                currentStage = "Selection cleared"
                            },
                            onDump = dumpClick@{
                                val source = selectedSource ?: return@dumpClick
                                scope.launch {
                                    errorMessage = null
                                    outputFolder = null
                                    logLines.clear()
                                    isDumping = true
                                    currentStage = "Starting local dumper…"
                                    try {
                                        val result = runNativeDumper(
                                            context = context,
                                            source = source,
                                            lowMemory = lowMemory,
                                            onProcessStart = { process -> activeProcess = process },
                                            onOutput = { line ->
                                                logLines.add(line)
                                                while (logLines.size > 40) logLines.removeAt(0)
                                                currentStage = stageFromOutput(line).ifBlank { currentStage }
                                            },
                                        )
                                        activeProcess = null
                                        val completedFolder = result.dumpFolder
                                        if (result.exitCode == 0 && completedFolder?.isDirectory == true) {
                                            outputFolder = completedFolder
                                            currentStage = "Dump complete · ${completedFolder.name}"
                                        } else {
                                            currentStage = "Dump failed · exit ${result.exitCode}"
                                            errorMessage = if (result.exitCode == 0) {
                                                "The dumper finished but did not create an output directory. Review the log below."
                                            } else {
                                                "Dumper exited with code ${result.exitCode}. Review the log below for details."
                                            }
                                        }
                                    } catch (error: Exception) {
                                        activeProcess = null
                                        currentStage = "Dump stopped"
                                        errorMessage = error.localizedMessage ?: "Could not start the bundled dumper."
                                    } finally {
                                        isDumping = false
                                    }
                                }
                            },
                        )
                    }
                }

                if (isDumping) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp, color = Cyan)
                                    Spacer(Modifier.width(10.dp))
                                    Text(currentStage.ifBlank { "Analyzing game files…" }, modifier = Modifier.weight(1f), color = Color.White)
                                    Text(
                                        "STOP",
                                        modifier = Modifier.clickable { activeProcess?.destroy() }.padding(8.dp),
                                        color = Bad,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                    )
                                }
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Cyan, trackColor = PanelRaised)
                            }
                        }
                    }
                } else if (outputFolder != null) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF10251F)), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("✓  Dump ready", color = Good, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text("${outputFolder?.listFiles()?.size ?: 0} output item(s) · saved privately on this device", color = Muted, fontSize = 12.sp)
                                Button(
                                    onClick = { exportPicker.launch("NullDumper-${System.currentTimeMillis()}.zip") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Good, contentColor = Night),
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text("Export dump ZIP", fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    item { NoticeCard("ACTION NEEDED", errorMessage!!, Bad) }
                }
                if (currentStage.isNotBlank() && !isDumping && outputFolder == null) {
                    item { Text(currentStage, color = Muted, fontSize = 12.sp) }
                }

                if (logLines.isNotEmpty()) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("RUN LOG", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                                Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                                    logLines.takeLast(24).forEach { line ->
                                        Text(line, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                item { Text("Files are processed locally. Only files you choose are read.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 12.dp)) }
            }
        }
    }
}

@Composable
private fun InputModePicker(selected: InputMode, enabled: Boolean, onSelected: (InputMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        InputMode.entries.forEach { mode ->
            val active = mode == selected
            Button(
                onClick = { onSelected(mode) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 9.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Cyan else Panel,
                    contentColor = if (active) Night else Muted,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(mode.title, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ApkFilePickerCard(enabled: Boolean, onChoose: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("APK FILES", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Text("Choose one APK, or select the base APK and all required split APKs together.", color = Color.White, fontSize = 13.sp)
            Text("Only the files you select are copied and scanned. .apks bundle archives are not supported.", color = Muted, fontSize = 11.sp)
            Button(
                onClick = onChoose,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Night),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Choose APK file(s)", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ManualPairPickerCard(
    binaryName: String,
    metadataName: String,
    codm: Boolean,
    onCodmChange: (Boolean) -> Unit,
    forceDump: Boolean,
    onForceDumpChange: (Boolean) -> Unit,
    dumpBase: String,
    onDumpBaseChange: (String) -> Unit,
    codeRegistration: String,
    onCodeRegistrationChange: (String) -> Unit,
    metadataRegistration: String,
    onMetadataRegistrationChange: (String) -> Unit,
    enabled: Boolean,
    onChooseBinary: () -> Unit,
    onChooseMetadata: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("MANUAL / ADVANCED INPUT", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Text("Useful when an APK hides or renames the native library, or its metadata is supplied separately.", color = Color.White, fontSize = 12.sp)
            Text("Custom library names (including libanogs.so) are accepted. Choose the actual IL2CPP binary; renaming it is unnecessary.", color = Muted, fontSize = 11.sp)
            OutlinedButton(onClick = onChooseBinary, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text(if (binaryName.isBlank()) "Choose native binary (.so / memory dump)" else "Binary: $binaryName", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onChooseMetadata, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text(if (metadataName.isBlank()) "Choose global-metadata.dat" else "Metadata: $metadataName", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("If metadata is encrypted or runtime-only, an APK alone cannot decrypt it. Import a decrypted metadata file you are authorized to use.", color = Warning, fontSize = 11.sp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CODM metadata layout", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text("Only enable for Call of Duty Mobile files", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = codm, onCheckedChange = onCodmChange, enabled = enabled)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Memory-dumped binary", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text("Use only with a dump and its known base address", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = forceDump, onCheckedChange = onForceDumpChange, enabled = enabled)
            }
            if (forceDump) {
                OutlinedTextField(
                    value = dumpBase,
                    onValueChange = onDumpBaseChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Dump base address (hex)") },
                    placeholder = { Text("0x7000000000") },
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                )
            }
            Text("Optional known ELF registration addresses for this exact binary. Leave blank for automatic scanning; do not guess or use values from another game build.", color = Muted, fontSize = 11.sp)
            OutlinedTextField(
                value = codeRegistration,
                onValueChange = onCodeRegistrationChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("CodeRegistration (hex address)") },
                placeholder = { Text("0x...") },
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = metadataRegistration,
                onValueChange = onMetadataRegistrationChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("MetadataRegistration (hex address)") },
                placeholder = { Text("0x...") },
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}

@Composable
private fun HeroCard(userAppCount: Int, loading: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("FIND IT. DUMP IT.", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
            Text("Game files,\nready to analyze.", color = Color.White, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (loading) "Loading user-installed apps…" else "$userAppCount user app(s) · choose how to provide the game files",
                color = Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun InstalledAppCard(app: DeviceApp, selected: Boolean, scanning: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF17283B) else Panel),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(38.dp), color = if (app.scan.hasIl2Cpp) Color(0xFF11313A) else PanelRaised, shape = CircleShape) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text(if (app.scan.hasIl2Cpp) "IL" else app.seed.label.take(1).uppercase(), color = if (app.scan.hasIl2Cpp) Cyan else Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.seed.label, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.seed.packageName, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("v${app.seed.version} · ${app.seed.apkPaths.size} APK/split", color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.width(8.dp))
            StatusBadge(
                text = when {
                    scanning -> "SCANNING"
                    !app.scan.scanned -> "TAP TO SCAN"
                    app.scan.ready -> "READY"
                    app.scan.hasIl2Cpp -> "PARTIAL"
                    else -> "NO IL2CPP"
                },
                color = when {
                    scanning -> Cyan
                    !app.scan.scanned -> Muted
                    app.scan.ready -> Good
                    app.scan.hasIl2Cpp -> Warning
                    else -> Muted
                },
            )
        }
    }
}

@Composable
private fun SelectedSourceCard(
    source: DumpSource,
    lowMemory: Boolean,
    onLowMemoryChange: (Boolean) -> Unit,
    isDumping: Boolean,
    onClear: () -> Unit,
    onDump: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SELECTED INPUT", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Text(source.label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            source.packageName?.let { Text(it, color = Muted, fontSize = 11.sp) }
            Text(
                if (source.isManualPair) "Manual binary + metadata pair" else "${source.apkPaths.size} APK/split file(s) · ${source.scan.abis.sorted().joinToString().ifBlank { "ABI not detected" }}",
                color = Muted,
                fontSize = 11.sp,
            )
            if (!source.isManualPair) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusBadge(if (source.scan.hasIl2Cpp) "libil2cpp.so found" else "native library missing", if (source.scan.hasIl2Cpp) Good else Warning)
                    StatusBadge(if (source.scan.hasMetadata) "metadata found" else "metadata missing", if (source.scan.hasMetadata) Good else Warning)
                }
            } else {
                Text("Custom filenames are okay; the selected native file is passed directly to the dumper.", color = Good, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onClear, enabled = !isDumping, shape = RoundedCornerShape(12.dp)) {
                Text("Remove selected input", color = Muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Low-memory mode", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Single worker · unchanged output", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = lowMemory, onCheckedChange = onLowMemoryChange, enabled = !isDumping)
            }
            Button(
                onClick = onDump,
                enabled = !isDumping,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Night),
                shape = RoundedCornerShape(13.dp),
            ) { Text(if (isDumping) "Analyzing…" else "Start dump", fontWeight = FontWeight.Bold) }
            if (!source.scan.ready) {
                Text(
                    "Missing metadata? Select Manual pair and import a metadata file you are authorized to use. Protected or runtime-only metadata cannot be recovered from an APK alone.",
                    color = Warning,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.13f), shape = RoundedCornerShape(50)) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun NoticeCard(title: String, body: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.1.sp)
            Text(body, color = Color.White, fontSize = 12.sp)
        }
    }
}

private suspend fun installedAppSeeds(context: Context): List<AppSeed> = withContext(Dispatchers.IO) {
    val manager = context.packageManager
    val apps = if (Build.VERSION.SDK_INT >= 33) {
        manager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        manager.getInstalledApplications(0)
    }
    val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    apps.asSequence()
        .filter { it.packageName != context.packageName && (it.flags and systemFlags) == 0 }
        .mapNotNull { info ->
            val paths = (listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()).distinct().map { File(it) }
            if (paths.isEmpty()) null else {
                val version = runCatching { packageVersion(manager, info.packageName) }.getOrDefault("?")
                AppSeed(info.loadLabel(manager).toString(), info.packageName, version, paths)
            }
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

@Suppress("DEPRECATION")
private fun packageVersion(manager: PackageManager, packageName: String): String {
    val info: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        manager.getPackageInfo(packageName, 0)
    }
    return info.versionName ?: "?"
}

private fun inspectApp(seed: AppSeed): DeviceApp = DeviceApp(seed, inspectApks(seed.apkPaths))

private fun inspectApks(paths: List<File>): ScanSummary {
    var hasLibrary = false
    var hasMetadata = false
    val abis = linkedSetOf<String>()
    paths.forEach { apk ->
        if (!apk.isFile) return@forEach
        runCatching {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    val parts = name.split('/')
                    if (parts.size == 3 && parts[0] == "lib" && parts[2] == "libil2cpp.so") {
                        hasLibrary = true
                        abis += parts[1]
                    }
                    if (name.substringAfterLast('/').equals("global-metadata.dat", ignoreCase = true)) {
                        hasMetadata = true
                    }
                }
            }
        }
    }
    return ScanSummary(hasLibrary, hasMetadata, abis)
}

private fun copySelectedApks(context: Context, uris: List<Uri>): List<File> {
    val cache = File(context.cacheDir, "apk-imports").apply { mkdirs() }
    cache.listFiles()?.forEach { it.delete() }
    val imported = mutableListOf<File>()
    uris.forEachIndexed { index, uri ->
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "selected-${index + 1}.apk"
        if (!displayName.endsWith(".apk", ignoreCase = true)) {
            throw IllegalArgumentException("${displayName} is not an .apk file. Choose APK files only.")
        }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(cache, "${UUID.randomUUID()}-$safeName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not read $displayName")
        imported += target
    }
    if (imported.isEmpty()) throw IllegalArgumentException("No APK files selected.")
    return imported
}

private fun copyPickedFile(context: Context, uri: Uri, subdirectory: String): File {
    val directory = File(context.cacheDir, subdirectory).apply { mkdirs() }
    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "selected-file"
    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val target = File(directory, "${UUID.randomUUID()}-$safeName")
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IllegalStateException("Could not read $displayName")
    if (target.length() == 0L) {
        target.delete()
        throw IllegalArgumentException("$displayName is empty.")
    }
    return target
}

private fun normalizeHexArgument(raw: String, label: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val digits = value.removePrefix("0x").removePrefix("0X")
    if (digits.isEmpty() || digits.any { it.digitToIntOrNull(16) == null } || digits.all { it == '0' }) {
        throw IllegalArgumentException("$label must be a non-zero hexadecimal address, for example 0x1234.")
    }
    return "0x$digits"
}

private data class DumpResult(val exitCode: Int, val dumpFolder: File?)

private suspend fun runNativeDumper(
    context: Context,
    source: DumpSource,
    lowMemory: Boolean,
    onProcessStart: (Process) -> Unit,
    onOutput: (String) -> Unit,
): DumpResult {
    if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
        throw IllegalStateException("This build currently includes the arm64-v8a dumper. Device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
    }
    val executable = File(context.applicationInfo.nativeLibraryDir, "libil2cpp_dumper.so")
    if (!executable.exists() || !executable.canExecute()) {
        throw IllegalStateException("The arm64 dumper binary is missing from this APK. Install a CI-built Null Dumper APK.")
    }

    val outputBase = File(context.filesDir, "dumps/run-${System.currentTimeMillis()}").apply { mkdirs() }
    val command = mutableListOf(executable.absolutePath)
    if (source.isManualPair) {
        command += listOf("--binary", source.binaryFile!!.absolutePath, "--metadata", source.metadataFile!!.absolutePath)
        val options = source.options
        if (options.codm) command += "--codm"
        val dumpBase = normalizeHexArgument(options.dumpBase, "Dump base")
        if (options.forceDump) {
            if (dumpBase == null) throw IllegalArgumentException("Enter the dump base address before enabling memory-dump mode.")
            command += listOf("--force-dump", "--dump-base", dumpBase)
        } else if (dumpBase != null) {
            throw IllegalArgumentException("Enable memory-dump mode to use a dump base address.")
        }
        normalizeHexArgument(options.codeRegistration, "CodeRegistration")?.let {
            command += listOf("--code-registration", it)
        }
        normalizeHexArgument(options.metadataRegistration, "MetadataRegistration")?.let {
            command += listOf("--metadata-registration", it)
        }
    } else {
        source.apkPaths.forEach { apk ->
            command += "--apk"
            command += apk.absolutePath
        }
    }
    command += listOf("--output", outputBase.absolutePath)
    if (lowMemory) command += "--low-memory" else command += listOf("--jobs", "2")

    val tempDir = File(context.cacheDir, "nulldumper-tmp").apply { mkdirs() }
    val process = withContext(Dispatchers.IO) {
        ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["TMPDIR"] = tempDir.absolutePath
                environment()["TMP"] = tempDir.absolutePath
                environment()["TEMP"] = tempDir.absolutePath
            }
            .start()
    }
    onProcessStart(process)
    val reader = process.inputStream.bufferedReader()
    while (true) {
        val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
        val clean = line.replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "").replace('\r', ' ').trim()
        if (clean.isNotEmpty()) onOutput(clean)
    }
    val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
    val resultDir = outputBase.listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("Dump") }
        ?.maxByOrNull { it.lastModified() }
    return DumpResult(exitCode, resultDir)
}

private fun stageFromOutput(line: String): String = when {
    "Inspecting" in line || "Extracting" in line -> "Scanning APK payloads…"
    "Loading IL2CPP" in line -> "Loading native binary…"
    "Loading metadata" in line || "Metadata loaded" in line -> "Reading metadata…"
    "Analyzing" in line || "Searching" in line -> "Analyzing registration data…"
    "Generating" in line || "Dumping" in line -> "Generating dump outputs…"
    "ERROR" in line || "Error" in line -> "The dumper reported an error…"
    else -> ""
}

private fun zipDump(context: Context, folder: File, destination: Uri) {
    val output = context.contentResolver.openOutputStream(destination)
        ?: throw IllegalStateException("Could not open the selected export destination.")
    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
        folder.walkTopDown().filter { it.isFile }.forEach { file ->
            val name = file.relativeTo(folder).path.replace(File.separatorChar, '/')
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

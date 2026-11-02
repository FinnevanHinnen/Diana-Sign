package com.homburg.diana_sign

import androidx.compose.ui.text.TextStyle
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import com.homburg.diana_sign.ui.theme.DianaSignTheme
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

// ==========================================
// DATA MODELS & PREFERENCES
// ==========================================
data class DeliveryRecord(
    val id: String,
    val bonId: String,
    val signatureBitmap: Bitmap
)

enum class AppTheme { SYSTEM, LIGHT, DARK }
enum class AppLanguage { DE, EN }

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("diana_sign_prefs", Context.MODE_PRIVATE)

    var companyName: String
        get() = prefs.getString("company_name", "") ?: ""
        set(value) = prefs.edit { putString("company_name", value) }

    var ownerName: String
        get() = prefs.getString("owner_name", "") ?: ""
        set(value) = prefs.edit { putString("owner_name", value) }

    var streetAddress: String
        get() = prefs.getString("street_address", "") ?: ""
        set(value) = prefs.edit { putString("street_address", value) }

    var zipAndCity: String
        get() = prefs.getString("zip_city", "") ?: ""
        set(value) = prefs.edit { putString("zip_city", value) }

    var driverName: String
        get() = prefs.getString("driver_name", "") ?: ""
        set(value) = prefs.edit { putString("driver_name", value) }

    var recipientEmail: String
        get() = prefs.getString("recipient_email", "") ?: ""
        set(value) = prefs.edit { putString("recipient_email", value) }

    var appTheme: AppTheme
        get() {
            val name = prefs.getString("app_theme", AppTheme.SYSTEM.name) ?: AppTheme.SYSTEM.name
            return try { AppTheme.valueOf(name) } catch (e: Exception) { AppTheme.SYSTEM }
        }
        set(value) = prefs.edit { putString("app_theme", value.name) }

    var appLanguage: AppLanguage
        get() {
            val name = prefs.getString("app_language", AppLanguage.DE.name) ?: AppLanguage.DE.name
            return try { AppLanguage.valueOf(name) } catch (e: Exception) { AppLanguage.DE }
        }
        set(value) = prefs.edit { putString("app_language", value.name) }
}

// ==========================================
// MAIN ACTIVITY
// ==========================================
class MainActivity : ComponentActivity() {
    private lateinit var settingsRepo: SettingsRepository
    val activeDeliveries = mutableStateListOf<DeliveryRecord>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(this)

        setContent {
            var currentTheme by remember { mutableStateOf(settingsRepo.appTheme) }
            var currentLang by remember { mutableStateOf(settingsRepo.appLanguage) }

            val useDark = when (currentTheme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }

            DianaSignTheme(darkTheme = useDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        settingsRepo = settingsRepo,
                        deliveries = activeDeliveries,
                        onSettingsChanged = {
                            currentTheme = settingsRepo.appTheme
                            currentLang = settingsRepo.appLanguage
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// NAVIGATION
// ==========================================
@Composable
fun AppNavigation(
    settingsRepo: SettingsRepository,
    deliveries: MutableList<DeliveryRecord>,
    onSettingsChanged: () -> Unit
) {
    var currentScreen by remember { mutableStateOf("main") }

    when (currentScreen) {
        "main" -> MainScreen(
            settingsRepo = settingsRepo,
            deliveries = deliveries,
            onOpenSettings = { currentScreen = "settings" }
        )
        "settings" -> SettingsScreen(
            settingsRepo = settingsRepo,
            onBack = {
                onSettingsChanged()
                currentScreen = "main"
            }
        )
    }
}

// ==========================================
// MAIN SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settingsRepo: SettingsRepository,
    deliveries: MutableList<DeliveryRecord>,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val lang = settingsRepo.appLanguage

    var showBonIdDialog by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var currentBonId by remember { mutableStateOf("") }
    var showResetDialog by remember { mutableStateOf(false) }

    val todayDate = remember {
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diana-Sign", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                }
            )
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (lang == AppLanguage.DE) "Datum: $todayDate" else "Date: $todayDate",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (lang == AppLanguage.DE) "Erfasste Auslieferungen heute" else "Deliveries documented today",
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${deliveries.size}",
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (deliveries.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        items(deliveries) { record ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (lang == AppLanguage.DE) "Bon-ID: ${record.bonId}" else "Receipt-ID: ${record.bonId}",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "✓ OK",
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { showBonIdDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (lang == AppLanguage.DE) "+ Neue Auslieferung" else "+ New Delivery",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (deliveries.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        if (lang == AppLanguage.DE) "Keine Auslieferungen erfasst." else "No deliveries recorded.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    finishAndExport(
                                        context = context,
                                        settingsRepo = settingsRepo,
                                        deliveries = deliveries
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (lang == AppLanguage.DE) "Tour Fertigstellen & Senden" else "Finish & Export Tour",
                                fontSize = 15.sp
                            )
                        }

                        Button(
                            onClick = {
                                if (deliveries.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        if (lang == AppLanguage.DE) "Die Liste ist bereits leer." else "List is already empty.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    showResetDialog = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Tour leeren", tint = Color.White)
                        }
                    }
                }
            }
        }
    )

    if (showBonIdDialog) {
        BonIdInputDialog(
            lang = lang,
            onDismiss = { showBonIdDialog = false },
            onConfirm = { bonId: String ->
                currentBonId = bonId
                showBonIdDialog = false
                showSignatureDialog = true
            }
        )
    }

    if (showSignatureDialog) {
        SignatureCaptureDialog(
            lang = lang,
            onDismiss = { showSignatureDialog = false },
            onConfirm = { bitmap ->
                val cropped = cropBitmapVertical(bitmap)
                deliveries.add(
                    DeliveryRecord(
                        id = UUID.randomUUID().toString(),
                        bonId = currentBonId,
                        signatureBitmap = cropped
                    )
                )
                showSignatureDialog = false
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text(if (lang == AppLanguage.DE) "Tour zurücksetzen?" else "Reset Tour?")
            },
            text = {
                Text(if (lang == AppLanguage.DE)
                    "Möchtest du alle erfassten Lieferungen und Unterschriften endgültig löschen? Stelle sicher, dass der Bericht erfolgreich versendet wurde."
                else "Do you want to clear all recorded deliveries?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deliveries.clear()
                        showResetDialog = false
                        Toast.makeText(
                            context,
                            if (lang == AppLanguage.DE) "Tour wurde zurückgesetzt." else "Tour reset.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(
                        if (lang == AppLanguage.DE) "Ja, leeren" else "Clear",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(if (lang == AppLanguage.DE) "Abbrechen" else "Cancel")
                }
            }
        )
    }
}

// ==========================================
// SETTINGS SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepo: SettingsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var company by remember { mutableStateOf(settingsRepo.companyName) }
    var owner by remember { mutableStateOf(settingsRepo.ownerName) }
    var street by remember { mutableStateOf(settingsRepo.streetAddress) }
    var zipCity by remember { mutableStateOf(settingsRepo.zipAndCity) }
    var driver by remember { mutableStateOf(settingsRepo.driverName) }
    var email by remember { mutableStateOf(settingsRepo.recipientEmail) }
    var theme by remember { mutableStateOf(settingsRepo.appTheme) }
    var lang by remember { mutableStateOf(settingsRepo.appLanguage) }

    var showChangelogDialog by remember { mutableStateOf(false) }

    val primaryRed = Color(0xFFD32F2F)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (lang == AppLanguage.DE) "Einstellungen" else "Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(0.5.dp, primaryRed)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (lang == AppLanguage.DE) "Firmendaten" else "Company Data",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = primaryRed
                    )

                    OutlinedTextField(
                        value = company,
                        onValueChange = {
                            company = it
                            settingsRepo.companyName = it
                        },
                        label = { Text(if (lang == AppLanguage.DE) "Firmenname" else "Company Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = owner,
                        onValueChange = {
                            owner = it
                            settingsRepo.ownerName = it
                        },
                        label = { Text(if (lang == AppLanguage.DE) "Inhaber" else "Owner") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = street,
                        onValueChange = {
                            street = it
                            settingsRepo.streetAddress = it
                        },
                        label = { Text(if (lang == AppLanguage.DE) "Straße / Hausnummer" else "Street / House No.") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = zipCity,
                        onValueChange = {
                            zipCity = it
                            settingsRepo.zipAndCity = it
                        },
                        label = { Text(if (lang == AppLanguage.DE) "PLZ / Ort" else "ZIP / City") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            settingsRepo.recipientEmail = it
                        },
                        label = { Text(if (lang == AppLanguage.DE) "Empfänger E-Mail" else "Recipient Email") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            OutlinedTextField(
                value = driver,
                onValueChange = {
                    driver = it
                    settingsRepo.driverName = it
                },
                label = { Text(if (lang == AppLanguage.DE) "Fahrername" else "Driver Name") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(if (lang == AppLanguage.DE) "App-Optionen" else "App Options", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (lang == AppLanguage.DE) "Design" else "Theme")
                Row {
                    FilterChip(
                        selected = theme == AppTheme.SYSTEM,
                        onClick = {
                            theme = AppTheme.SYSTEM
                            settingsRepo.appTheme = AppTheme.SYSTEM
                        },
                        label = { Text("Auto") }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    FilterChip(
                        selected = theme == AppTheme.LIGHT,
                        onClick = {
                            theme = AppTheme.LIGHT
                            settingsRepo.appTheme = AppTheme.LIGHT
                        },
                        label = { Text("Hell") }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    FilterChip(
                        selected = theme == AppTheme.DARK,
                        onClick = {
                            theme = AppTheme.DARK
                            settingsRepo.appTheme = AppTheme.DARK
                        },
                        label = { Text("Dunkel") }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (lang == AppLanguage.DE) "Sprache" else "Language")
                Row {
                    FilterChip(
                        selected = lang == AppLanguage.DE,
                        onClick = {
                            lang = AppLanguage.DE
                            settingsRepo.appLanguage = AppLanguage.DE
                        },
                        label = { Text("Deutsch") }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    FilterChip(
                        selected = lang == AppLanguage.EN,
                        onClick = {
                            lang = AppLanguage.EN
                            settingsRepo.appLanguage = AppLanguage.EN
                        },
                        label = { Text("English") }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://homburgers.de/diana-sign/impressum.html"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (lang == AppLanguage.DE) "Web-Impressum" else "Web Imprint", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://homburgers.de/diana-sign/datenschutz.html"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (lang == AppLanguage.DE) "Web-Datenschutz" else "Web Privacy", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("IMPRESSUM", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Angaben gemäß § 5 DDG:\n" +
                        "Andreas Homburg\n" +
                        "Augustastraße 20\n" +
                        "16515 Oranienburg\n" +
                        "Deutschland\n\n" +
                        "E-Mail: homburg.andreas@gmail.com\n\n" +
                        "Verantwortlich nach § 18 Abs. 2 MStV:\n" +
                        "Andreas Homburg, Augustastraße 20, 16515 Oranienburg",
                fontSize = 12.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text("DATENSCHUTZERKLÄRUNG (Auszug)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Diana-Sign ist eine 100 % offline arbeitende App.\n" +
                        "- Es werden keinerlei Daten an externe Server übertragen.\n" +
                        "- Keine Cookies, kein Tracking, kein Werbung.\n" +
                        "- Erfasste Bon-IDs und Unterschriften verbleiben temporär im lokalen Cache.\n" +
                        "- Nach dem Export/Fertigstellen werden alle Bitmaps und PDFs restlos gelöscht.",
                fontSize = 12.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Klickbarer Versions-Footer am Ende hinter dem Zahnrad
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showChangelogDialog = true }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Diana-Sign v1.1.0",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (lang == AppLanguage.DE) "Versionshinweise anzeigen" else "View Changelog",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (showChangelogDialog) {
        ChangelogDialog(
            lang = lang,
            onDismiss = { showChangelogDialog = false }
        )
    }
}

// ==========================================
// PDF GENERATION & EXPORT
// ==========================================
fun finishAndExport(
    context: Context,
    settingsRepo: SettingsRepository,
    deliveries: MutableList<DeliveryRecord>,
) {
    val dateStr = SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(Date())
    val sanitize = { str: String -> str.replace(" ", "_").replace("[^a-zA-Z0-9_]".toRegex(), "") }

    val company = sanitize(settingsRepo.companyName.ifBlank { "Firma" })
    val driver = sanitize(settingsRepo.driverName.ifBlank { "Fahrer" })
    val fileName = "${dateStr}_${company}_${driver}.pdf"

    val cacheDir = context.cacheDir
    val pdfFile = File(cacheDir, fileName)

    try {
        val pdfDoc = PdfDocument()

        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f

        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
        var page = pdfDoc.startPage(pageInfo)
        var canvas = page.canvas

        val primaryRed = "#D32F2F".toColorInt()
        val darkBlack = "#111111".toColorInt()
        val grayTextColor = "#666666".toColorInt()
        val borderColor = "#333333".toColorInt()
        val fillLightGray = "#FAFAFA".toColorInt()

        val paintTitle = Paint().apply {
            color = darkBlack
            textSize = 20f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val paintHeaderMeta = Paint().apply {
            color = darkBlack
            textSize = 11f
            isAntiAlias = true
        }

        val paintBold = Paint().apply {
            color = darkBlack
            textSize = 13f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val paintRedBold = Paint().apply {
            color = primaryRed
            textSize = 14f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val paintText = Paint().apply {
            color = darkBlack
            textSize = 11f
            isAntiAlias = true
        }

        val paintSmallGray = Paint().apply {
            color = grayTextColor
            textSize = 8.5f
            isAntiAlias = true
        }

        val paintLine = Paint().apply {
            color = borderColor
            strokeWidth = 1f
            isAntiAlias = true
        }

        val paintAccentBar = Paint().apply {
            color = primaryRed
            strokeWidth = 5f
            isAntiAlias = true
        }

        val paintBackground = Paint().apply {
            color = fillLightGray
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val paintBoxBorder = Paint().apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }

        val appIconBitmap = try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                ?.let { Bitmap.createScaledBitmap(it, 16, 16, true) }
        } catch (e: Exception) {
            null
        }

        fun drawHeader(c: Canvas, pNum: Int) {
            c.drawLine(margin, 30f, pageWidth - margin, 30f, paintAccentBar)

            val compName = settingsRepo.companyName.ifBlank { "Unternehmensname nicht angegeben" }
            val ownerName = settingsRepo.ownerName
            val streetAddr = settingsRepo.streetAddress
            val zipAndCityStr = settingsRepo.zipAndCity

            c.drawText(compName, margin, 52f, paintTitle)

            var headerY = 68f
            if (ownerName.isNotBlank()) {
                c.drawText("Inh. $ownerName", margin, headerY, paintHeaderMeta)
                headerY += 14f
            }
            if (streetAddr.isNotBlank()) {
                c.drawText(streetAddr, margin, headerY, paintHeaderMeta)
                headerY += 14f
            }
            if (zipAndCityStr.isNotBlank()) {
                c.drawText(zipAndCityStr, margin, headerY, paintHeaderMeta)
            }

            val dateFormatted = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
            c.drawText("Digitaler Lieferbericht", pageWidth - margin - 140f, 52f, paintBold)
            c.drawText("Datum: $dateFormatted", pageWidth - margin - 140f, 68f, paintHeaderMeta)
            c.drawText("Seite $pNum", pageWidth - margin - 140f, 84f, paintHeaderMeta)

            c.drawRect(margin, 110f, pageWidth - margin, 142f, paintBackground)
            c.drawRect(margin, 110f, pageWidth - margin, 142f, paintBoxBorder)
            c.drawText("Fahrer: ${settingsRepo.driverName.ifBlank { "Nicht angegeben" }}", margin + 12f, 131f, paintBold)

            c.drawLine(margin, 152f, pageWidth - margin, 152f, paintLine)

            val footerY = pageHeight.toFloat() - 22f
            if (appIconBitmap != null) {
                c.drawBitmap(appIconBitmap, margin, footerY - 11f, null)
            }
            val textStartX = if (appIconBitmap != null) margin + 22f else margin
            val footerText = "Dieses Dokument wurde erstellt mit Hilfe von Diana-Sign. Ihre einfache Lösung zum Erstellen eines Liefernachweises (100% offline)"
            c.drawText(footerText, textStartX, footerY, paintSmallGray)
        }

        drawHeader(canvas, pageNum)

        var yOffset = 168f
        val rowHeight = 95f

        deliveries.forEachIndexed { index, record ->
            if (yOffset + rowHeight > pageHeight - 50f) {
                pdfDoc.finishPage(page)
                pageNum++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                page = pdfDoc.startPage(pageInfo)
                canvas = page.canvas
                drawHeader(canvas, pageNum)
                yOffset = 168f
            }

            canvas.drawRect(margin, yOffset, pageWidth - margin, yOffset + rowHeight - 10f, paintBackground)
            canvas.drawRect(margin, yOffset, pageWidth - margin, yOffset + rowHeight - 10f, paintBoxBorder)

            canvas.drawText("Position #${index + 1}", margin + 12f, yOffset + 30f, paintRedBold)
            canvas.drawText("Bon-ID: ${record.bonId}", margin + 12f, yOffset + 52f, paintText)

            canvas.drawText("✓ Unterschrieben", margin + 12f, yOffset + 72f, Paint().apply {
                color = "#2E7D32".toColorInt()
                textSize = 9f
                isFakeBoldText = true
                isAntiAlias = true
            })

            val signatureDividerX = margin + 160f
            canvas.drawLine(signatureDividerX, yOffset, signatureDividerX, yOffset + rowHeight - 10f, paintLine)

            val sigBitmap = record.signatureBitmap.scale(240, 52)
            canvas.drawBitmap(sigBitmap, signatureDividerX + 25f, yOffset + 10f, null)

            canvas.drawLine(signatureDividerX + 25f, yOffset + 68f, pageWidth - margin - 25f, yOffset + 68f, paintLine)
            canvas.drawText("Unterschrift Empfänger", signatureDividerX + 25f, yOffset + 80f, paintSmallGray)

            yOffset += rowHeight
        }

        pdfDoc.finishPage(page)

        val fos = FileOutputStream(pdfFile)
        pdfDoc.writeTo(fos)
        fos.close()
        pdfDoc.close()

        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )

        val emailIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(settingsRepo.recipientEmail))
            putExtra(Intent.EXTRA_SUBJECT, "Lieferbericht $dateStr - ${settingsRepo.companyName}")
            putExtra(Intent.EXTRA_TEXT, "Anbei der strukturierte Lieferbericht von Diana-Sign.")
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(emailIntent, "Lieferbericht versenden mit...")
        context.startActivity(chooser)

        Toast.makeText(context, "PDF erstellt & Tour zurückgesetzt.", Toast.LENGTH_LONG).show()

    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Fehler beim Erstellen der PDF: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ==========================================
// BON ID DIALOG
// ==========================================
@Composable
fun BonIdInputDialog(
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var bonIdText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100.milliseconds)
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(28.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (lang == AppLanguage.DE) "Bon-ID Eingabe" else "Enter Receipt ID",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = bonIdText,
                    onValueChange = { bonIdText = it.filter { char -> char.isDigit() } },
                    label = {
                        Text(
                            if (lang == AppLanguage.DE) "Bon- / Auftrags-ID" else "Receipt / Order ID",
                            fontSize = 15.sp
                        )
                    },
                    textStyle = TextStyle(fontSize = 18.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )

                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) {
                        Text(
                            text = if (lang == AppLanguage.DE) "Abbrechen" else "Cancel",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = { bonIdText = "" },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBC02D))
                    ) {
                        Text(
                            text = if (lang == AppLanguage.DE) "Reset" else "Clear",
                            color = Color.Black,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = {
                            if (bonIdText.isNotBlank()) {
                                onConfirm(bonIdText)
                            } else {
                                Toast.makeText(
                                    context,
                                    if (lang == AppLanguage.DE) "Bitte Bon-ID eingeben!" else "Please enter ID!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                    ) {
                        Text(
                            text = "OK",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// SIGNATURE CAPTURE DIALOG
// ==========================================
@Composable
fun SignatureCaptureDialog(
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    val paths = remember { mutableStateListOf<Path>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var drawCanvasSize by remember { mutableStateOf(Pair(800, 600)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF5F5F5))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) {
                        Text(if (lang == AppLanguage.DE) "Abbrechen" else "Cancel", color = Color.White)
                    }

                    Button(
                        onClick = { paths.clear() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBC02D))
                    ) {
                        Text(if (lang == AppLanguage.DE) "Wiederholen" else "Clear", color = Color.Black)
                    }

                    Button(
                        onClick = {
                            if (paths.isNotEmpty()) {
                                val bitmap = createBitmapFromPaths(paths, drawCanvasSize.first, drawCanvasSize.second)
                                onConfirm(bitmap)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                    ) {
                        Text("OK", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .background(Color.White)
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInteropFilter { event ->
                                when (event.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        val path = Path().apply {
                                            moveTo(event.x, event.y)
                                        }
                                        currentPath = path
                                        paths.add(path)
                                        true
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        currentPath?.lineTo(event.x, event.y)
                                        val temp = paths.toList()
                                        paths.clear()
                                        paths.addAll(temp)
                                        true
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        currentPath = null
                                        true
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        drawCanvasSize = Pair(size.width.toInt(), size.height.toInt())
                        paths.forEach { path ->
                            drawPath(
                                path = path,
                                color = Color.Black,
                                style = Stroke(width = 6.dp.toPx())
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// CHANGELOG & INFO DIALOG
// ==========================================
@Composable
fun ChangelogDialog(
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = if (lang == AppLanguage.DE) "Versionshinweise & Info" else "Changelog & Info",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Diana-Sign v1.1.0",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(weight = 1f, fill = false)
                ) {
                    ChangelogItem(
                        version = "v1.1.0",
                        date = "November 2026",
                        title = if (lang == AppLanguage.DE) "Größere UI & PDF-Optimierung" else "Larger UI & PDF Polish",
                        details = if (lang == AppLanguage.DE)
                            "• Bon-ID Dialog und Buttons massiv vergrößert für perfekte Touch-Bedienung.\n• Professioneller PDF-Export mit dynamischem Briefkopf und Firmen-Footer.\n• Umstellung auf reine ID-Eingabe (Datenschutz-konform)."
                        else
                            "• Enhanced Bon-ID dialog sizing for touch usability.\n• Professional PDF export with custom headers and footers.\n• Streamlined ID entry workflow."
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ChangelogItem(
                        version = "v1.0.0",
                        date = "Oktober 2026",
                        title = if (lang == AppLanguage.DE) "Initiales Release" else "Initial Release",
                        details = if (lang == AppLanguage.DE)
                            "• Grundgerüst für Liefernachweise.\n• Lokale Unterschriftenerfassung.\n• E-Mail Versand per Share-Intent."
                        else
                            "• Core delivery tracking features.\n• Local signature capture.\n• Email export via share intent."
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = if (lang == AppLanguage.DE) "Schließen" else "Close",
                        fontSize = 15.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ChangelogItem(version: String, date: String, title: String, details: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "$version ($date)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.DarkGray)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = details, fontSize = 12.sp, color = Color.Gray)
    }
}

// ==========================================
// HELPER FUNCTIONS
// ==========================================
fun createBitmapFromPaths(paths: List<Path>, width: Int, height: Int): Bitmap {
    val safeWidth = if (width <= 0) 800 else width
    val safeHeight = if (height <= 0) 600 else height
    val bitmap = createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(AndroidColor.WHITE)

    val paint = Paint().apply {
        color = AndroidColor.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
    }

    paths.forEach { path ->
        canvas.drawPath(path.asAndroidPath(), paint)
    }
    return bitmap
}

fun cropBitmapVertical(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    var top = height
    var bottom = 0

    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = source[x, y]
            if (pixel != AndroidColor.WHITE) {
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
    }

    return if (top < bottom) {
        val padTop = (top - 10).coerceAtLeast(0)
        val padBottom = (bottom + 10).coerceAtMost(height - 1)
        val croppedHeight = padBottom - padTop
        Bitmap.createBitmap(source, 0, padTop, width, croppedHeight)
    } else {
        source
    }
}
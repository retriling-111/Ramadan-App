@file:Suppress("DEPRECATION")

package com.khant.ramadan

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.*
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import com.google.android.gms.location.LocationServices
import com.khant.ramadan.ui.theme.AppThemes
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// --- PREMIUM & HOLY UI COLORS ---
val EmeraldDark = Color(0xFF004D40)
val EmeraldLight = Color(0xFF00695C)
val GoldPremium = Color(0xFFD4AF37)
val SoftCanvas = Color(0xFFF5F7F5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup Notification & Scheduling
        createNotificationChannel(this)
        schedulePrayerWorker(this)

        setContent {
            AppThemes {
                MainLayout()
            }
        }
    }
}

// --- NOTIFICATION & WORKER LOGIC ---

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = "Prayer Alerts"
        val descriptionText = "Notifications for Prayer Times"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel("PRAYER_NOTI", name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

fun schedulePrayerWorker(context: Context) {
    val request = PeriodicWorkRequestBuilder<PrayerWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "PrayerNotificationWork",
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
}

class PrayerWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("location_prefs", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("lat", 0f).toDouble()
        val lon = prefs.getFloat("lon", 0f).toDouble()

        if (lat == 0.0) return Result.success()

        val coords = Coordinates(lat, lon)
        val method = getCalculationMethod(lat, lon)
        val pTimes = PrayerTimes(coords, DateComponents.from(Date()), method.parameters.apply { madhab = Madhab.HANAFI })

        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMin = now.get(Calendar.MINUTE)

        val prayers = mapOf(
            "Fajr" to pTimes.fajr,
            "Dhuhr" to pTimes.dhuhr,
            "Asr" to pTimes.asr,
            "Maghrib" to pTimes.maghrib,
            "Isha" to pTimes.isha
        )

        for ((name, pTime) in prayers) {
            val pCal = Calendar.getInstance().apply { time = pTime }
            // နာရီနဲ့ မိနစ် တူမတူ စစ်ဆေးခြင်း
            if (pCal.get(Calendar.HOUR_OF_DAY) == currentHour && pCal.get(Calendar.MINUTE) == currentMin) {
                showNotification(name)
            }
        }

        return Result.success()
    }

    private fun showNotification(prayerName: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Channel ID ကို create လုပ်ထားတဲ့ "PRAYER_NOTI" နဲ့ တူအောင် ပြင်ပေးထားပါတယ်
        val builder = NotificationCompat.Builder(applicationContext, "PRAYER_NOTI")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("It's time for $prayerName")
            .setContentText("Ramadan Kareem! Time to perform your $prayerName prayer.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)

        notificationManager.notify(prayerName.hashCode(), builder.build())
    }
}

// --- UI COMPONENTS & SCREENS ---

fun getCalculationMethod(lat: Double, lon: Double): CalculationMethod {
    return when {
        lat in 16.0..28.0 && lon in 92.0..101.0 -> CalculationMethod.KARACHI
        lat in 5.0..20.0 && lon in 70.0..130.0 -> CalculationMethod.SINGAPORE
        lat > 45.0 -> CalculationMethod.MUSLIM_WORLD_LEAGUE
        lat in 20.0..30.0 && lon in 30.0..50.0 -> CalculationMethod.UMM_AL_QURA
        else -> CalculationMethod.EGYPTIAN
    }
}

@Composable
fun MainLayout() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute != "splash") {
                FloatingBottomNav(navController)
            }
        },
        containerColor = SoftCanvas
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            NavigationGraph(navController)
        }
    }
}

@Composable
fun NavigationGraph(navController: NavHostController) {
    NavHost(navController, startDestination = "splash",
        enterTransition = { fadeIn(animationSpec = tween(400)) },
        exitTransition = { fadeOut(animationSpec = tween(400)) }
    ) {
        composable("splash") { SplashScreen(navController) }
        composable("home") { HomeScreen(navController) }
        composable("duas") { DuaScreen() }
        composable("settings") { SettingsScreen() }
        composable("tasbeeh") { TasbeehScreen() }
        composable("timetable") { TimeTableScreen() }
    }
}

@Composable
fun SplashScreen(navController: NavHostController) {
    LaunchedEffect(Unit) {
        delay(2000)
        navController.navigate("home") { popUpTo("splash") { inclusive = true } }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = "App Logo",
                modifier = Modifier.size(120.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text("RAMADAN KAREEM", fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = EmeraldDark)
            Spacer(Modifier.height(40.dp))
            CircularProgressIndicator(color = GoldPremium, strokeWidth = 2.dp)
        }
    }
}

@SuppressLint("DefaultLocale")
@Composable
fun HomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    var locationName by remember { mutableStateOf("Fetching Location...") }
    var prayerTimes by remember { mutableStateOf<PrayerTimes?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshKey++ }

    LaunchedEffect(refreshKey) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    context.getSharedPreferences("location_prefs", Context.MODE_PRIVATE).edit {
                        putFloat("lat", it.latitude.toFloat())
                        putFloat("lon", it.longitude.toFloat())
                    }

                    val coords = Coordinates(it.latitude, it.longitude)
                    val method = getCalculationMethod(it.latitude, it.longitude)
                    val params = method.parameters.apply { madhab = Madhab.HANAFI }
                    prayerTimes = PrayerTimes(coords, DateComponents.from(Date()), params)

                    try {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val addresses = geocoder.getFromLocation(it.latitude, it.longitude, 1)
                        val address = addresses?.firstOrNull()
                        locationName = "${address?.locality ?: "Unknown City"}, ${address?.countryName ?: ""}"
                    } catch (_: Exception) {
                        locationName = "GPS Active: ${String.format("%.2f", it.latitude)}, ${String.format("%.2f", it.longitude)}"
                    }
                }
            }
        } else {
            launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())

    fun getSehriTime(fajr: Date?): String {
        if (fajr == null) return "--:--"
        val cal = Calendar.getInstance().apply { time = fajr; add(Calendar.MINUTE, -10) }
        return sdf.format(cal.time)
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(30.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Assalamu Alaikum,", color = Color.Gray, fontSize = 14.sp)
                    Text("Ramadan 2026", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = EmeraldDark)
                }
                IconButton(onClick = { refreshKey++ }, modifier = Modifier.background(EmeraldDark.copy(0.1f), CircleShape)) {
                    Icon(Icons.Default.Refresh, null, tint = EmeraldDark)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Default.Place, null, tint = GoldPremium, modifier = Modifier.size(16.dp))
                Text(locationName, color = EmeraldLight, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
            }
            Spacer(Modifier.height(25.dp))
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().shadow(20.dp, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = EmeraldDark)
            ) {
                Box(Modifier.background(Brush.verticalGradient(listOf(EmeraldDark, Color(0xFF00332C))))) {
                    Column(Modifier.padding(25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("TODAY'S FASTING", color = GoldPremium, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TimeBox("Sehri (Ends)", getSehriTime(prayerTimes?.fajr))
                            VerticalDivider(Modifier.height(40.dp).align(Alignment.CenterVertically), color = Color.White.copy(0.2f))
                            TimeBox("Iftar (Starts)", prayerTimes?.maghrib?.let { sdf.format(it) } ?: "--:--")
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(30.dp))
            Text("Main Features", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = EmeraldDark)
            Spacer(Modifier.height(15.dp))
            val menuItems = listOf(
                Triple("Tasbeeh", Icons.Default.Fingerprint, "tasbeeh"),
                Triple("Timetable", Icons.Default.CalendarMonth, "timetable")
            )
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.height(110.dp), horizontalArrangement = Arrangement.spacedBy(15.dp), verticalArrangement = Arrangement.spacedBy(15.dp), userScrollEnabled = false) {
                items(menuItems) { (title, icon, route) ->
                    FeatureCard(title, icon) { navController.navigate(route) }
                }
            }
        }

        item {
            Spacer(Modifier.height(30.dp))
            Text("Prayer Schedule", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = EmeraldDark)
            Spacer(Modifier.height(10.dp))
        }

        val prayerNames = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
        val times = listOf(prayerTimes?.fajr, prayerTimes?.sunrise, prayerTimes?.dhuhr, prayerTimes?.asr, prayerTimes?.maghrib, prayerTimes?.isha)

        items(times.size) { i ->
            PrayerItem(prayerNames[i], times[i]?.let { sdf.format(it) } ?: "--:--")
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
fun PrayerItem(name: String, time: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, Color(0xFFEEEEEE))
    ) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, fontWeight = FontWeight.SemiBold, color = EmeraldDark)
            Text(time, fontWeight = FontWeight.Bold, color = Color.DarkGray)
        }
    }
}

@Composable
fun FeatureCard(title: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier.height(100.dp).clickable { onClick() },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = EmeraldDark, modifier = Modifier.size(28.dp))
            Text(title, color = EmeraldDark, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun TimeBox(label: String, time: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(0.6f), fontSize = 11.sp)
        Text(time, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@SuppressLint("SimpleDateFormat")
@Composable
fun TimeTableScreen() {
    val context = LocalContext.current
    var coords by remember { mutableStateOf<Coordinates?>(null) }
    val today = Calendar.getInstance()
    val ramadanStart = Calendar.getInstance().apply { set(2026, Calendar.FEBRUARY, 18) }

    LaunchedEffect(Unit) {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fused.lastLocation.addOnSuccessListener { it?.let { coords = Coordinates(it.latitude, it.longitude) } }
        }
    }

    var selectedDayParams by remember { mutableStateOf<Pair<Calendar, PrayerTimes>?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(20.dp)) {
        item {
            Text("Ramadan 1447 Schedule", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = EmeraldDark)
            Text("Based on your current location", fontSize = 13.sp, color = Color.Gray)
            Spacer(Modifier.height(20.dp))
        }

        items(30) { i ->
            val day = (ramadanStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
            val isToday = day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)

            val userLat = coords?.latitude ?: 16.8661
            val userLon = coords?.longitude ?: 96.1951
            val method = getCalculationMethod(userLat, userLon)
            val params = method.parameters.apply { madhab = Madhab.HANAFI }
            val pTimes = PrayerTimes(Coordinates(userLat, userLon), DateComponents.from(day.time), params)

            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { selectedDayParams = day to pTimes },
                colors = CardDefaults.cardColors(containerColor = if (isToday) EmeraldDark else Color.White),
                elevation = CardDefaults.cardElevation(if (isToday) 8.dp else 1.dp)
            ) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Day ${i + 1}", fontWeight = FontWeight.Bold, color = if (isToday) GoldPremium else EmeraldDark)
                        Text(SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(day.time), fontSize = 12.sp, color = if (isToday) Color.White.copy(0.7f) else Color.Gray)
                    }
                    Text("Iftar: ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(pTimes.maghrib)}", fontWeight = FontWeight.Bold, color = if (isToday) Color.White else EmeraldDark)
                }
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }

    selectedDayParams?.let { (day, p) ->
        Dialog(onDismissRequest = { selectedDayParams = null }) {
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(25.dp).fillMaxWidth()) {
                    Text("Day Details", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = EmeraldDark)
                    Text(SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(day.time), color = GoldPremium, fontSize = 14.sp)
                    HorizontalDivider(Modifier.padding(vertical = 15.dp), color = Color(0xFFEEEEEE))

                    val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
                    ScheduleRow("Sehri Ends", Calendar.getInstance().apply { time = p.fajr; add(Calendar.MINUTE, -30) }.time.let { fmt.format(it) })
                    ScheduleRow("Fajr", fmt.format(p.fajr))
                    ScheduleRow("Sunrise", fmt.format(p.sunrise))
                    ScheduleRow("Dhuhr", fmt.format(p.dhuhr))
                    ScheduleRow("Asr", fmt.format(p.asr))
                    ScheduleRow("Maghrib / Iftar", fmt.format(p.maghrib))
                    ScheduleRow("Isha", fmt.format(p.isha))

                    Button(onClick = { selectedDayParams = null }, Modifier.fillMaxWidth().padding(top = 20.dp), colors = ButtonDefaults.buttonColors(containerColor = EmeraldDark)) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleRow(l: String, t: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(l, color = Color.Gray, fontSize = 14.sp)
        Text(t, fontWeight = FontWeight.Bold, color = EmeraldDark, fontSize = 14.sp)
    }
}

@Composable
fun DuaScreen() {
    val duas = listOf(
        DuaData("Niyyah for Fasting", "وَبِصَوْمِ غَدٍ نَّوَيْتُ مِنْ شَهْرِ رَمَضَانَ", "Wa bi sawmi ghadinn nawaytu min shahri ramadan"),
        DuaData("Iftar (Breaking Fast)", "اللَّهُمَّ لَكَ صُمْتُ وَعَلَى رِزْقِكَ أَفْطَرْتُ", "Allahumma laka sumtu wa 'ala rizqika aftartu"),
        DuaData("After Iftar", "ذَهَبَ الظَّمَأُ وَابْتَلَّتِ الْعُرُوقُ وَثَبَتَ الأَجْرُ إِنْ شَاءَ اللَّهُ", "Dhahaba al-zamau wa'btallati al-'uruqu wa thabata al-ajru in sha Allah"),
        DuaData("Lailatul Qadr", "اللَّهُمَّ إِنَّكَ عَفُوٌّ تُحِبُّ الْعَفْوَ فَاعْفُ عَنِّي", "Allahumma innaka 'afuwwun tuhibbul 'afwa fa'fu 'anni"),
        DuaData("First Ashra (Mercy)", "رَبِّ اغْفِرْ وَارْحَمْ وَأَنْتَ خَيْرُ الرَّاحِمِينَ", "Rabbi-ghfir war-ham wa Anta khairur-rahimeen"),
        DuaData("Second Ashra (Forgiveness)", "أَسْتَغْفِرُ اللهَ رَبِّي مِنْ كُلِّ ذَنْبٍ وَأَتُوبُ إِلَيْهِ", "Astaghfirullaha Rabbi min kulli dhanbin wa atubu ilayh"),
        DuaData("Third Ashra (Protection)", "اللَّهُمَّ أَجِرْنِي مِنَ النَّارِ", "Allahumma ajirni minan-nar"),
        DuaData("Morning Dua", "أَصْبَحْنَا وَأَصْبَحَ الْمُلْكُ لِلَّهِ", "Asbahna wa asbahal mulku lillah"),
        DuaData("Evening Dua", "أَمْسَيْنَا وَأَمْسَى الْمُلْكُ لِلَّهِ", "Amsayna wa amsal-mulku lillah"),
        DuaData("Sayyidul Istighfar", "اللَّهُمَّ أَنْتَ رَبِّي لَا إِلَهَ إِلَّا أَنْتَ خَلَقْتَنِي", "Allahumma Anta Rabbi la ilaha illa Anta khalaqtani"),
        DuaData("For Parents", "رَّبِّ ارْحَمْهُمَا كَمَا رَبَّيَانِي صَغِيرًا", "Rabbi irhamhuma kama rabbayani sagheera"),
        DuaData("For Knowledge", "رَّبِّ زِدْنِي عِلْمًا", "Rabbi zidni 'ilma"),
        DuaData("General Success", "رَبَّنَا آتِنَا فِي الدُّنْيَا حَسَنَةً وَفِي الْآخِرَةِ حَسَنَةً", "Rabbana atina fid-dunya hasanatan wa fil-akhirati hasanatan"),
        DuaData("Heart Firmness", "يَا مُقَلِّبَ الْقُلُوبِ ثَبِّتْ قَلْبِي عَلَى دِينِكَ", "Ya Muqallibal-qulubi thabbit qalbi 'ala dinik"),
        DuaData("Seeking Ease", "اللَّهُمَّ لَا سَهْلَ إِلَّا مَا جَعَلْتَهُ سَهْلًا", "Allahumma la sahla illa ma ja'altahu sahla"),
        DuaData("Relief from Anxiety", "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْهَمِّ وَالْحَزَنِ", "Allahumma inni a'udhu bika minal-hammi wal-hazan"),
        DuaData("Reliance on Allah", "حَسْبُنَا اللَّهُ وَنِعْمَ الْوَكِيلُ", "Hasbunallahu wa ni'mal wakeel"),
        DuaData("Protection from Harm", "بِسْمِ اللَّهِ الَّذِي لَا يَضُرُّ مَعَ اسْمِهِ شَيْءٌ", "Bismillahil-ladhi la yadurru ma'as-mihi shay'un"),
        DuaData("Entering Home", "بِسْمِ اللَّهِ وَلَجْنَا وَبِسْمِ اللَّهِ خَرَجْنَا", "Bismillahi walajna wa bismillahi kharajna"),
        DuaData("Leaving Home", "بِسْمِ اللَّهِ تَوَكَّلْتُ عَلَى اللَّهِ", "Bismillahi tawakkaltu 'alallahi"),
        DuaData("Before Sleep", "بِاسْمِكَ اللَّهُمَّ أَمُوتُ وَأَحْيَا", "Bismika Allahumma amutu wa ahya"),
        DuaData("Waking Up", "الْحَمْدُ لِلَّهِ الَّذِي أَحْيَانَا بَعْدَ مَا أَمَاتَنَا", "Alhamdu lillahil-ladhi ahyana ba'da ma amatana"),
        DuaData("Before Wudhu", "بِسْمِ اللَّهِ", "Bismillah"),
        DuaData("After Wudhu", "أَشْهَدُ أَنْ لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ", "Ashhadu an la ilaha illallahu wahdahu la sharika lah"),
        DuaData("Entering Mosque", "اللَّهُمَّ افْتَحْ لِي أَبْوَابَ رَحْمَتِكَ", "Allahumma-ftah li abwaba rahmatik"),
        DuaData("Leaving Mosque", "اللَّهُمَّ إِنِّي أَسْأَلُكَ مِنْ فَضْلِكَ", "Allahumma inni as'aluka min fadlik"),
        DuaData("Before Eating", "بِسْمِ اللَّهِ وَعَلَى بَرَكَةِ اللَّهِ", "Bismillahi wa 'ala barakatillah"),
        DuaData("After Eating", "الْحَمْدُ لِلَّهِ الَّذِي أَطْعَمَنَا وَسَقَانَا", "Alhamdu lillahil-ladhi at'amana wa saqana"),
        DuaData("Iftar as a Guest", "أَفْطَرَ عِنْدَكُمُ الصَّائِمُونَ وَأَكَلَ طَعَامَكُمُ الأَبْرَارُ", "Aftara 'indakumu as-sa'imuna wa akala ta'amakumu al-abrar"),
        DuaData("For Health", "اللَّهُمَّ عَافِنِي فِي بَدَنِي", "Allahumma 'afini fi badani"),
        DuaData("For Hearing", "اللَّهُمَّ عَافِنِي فِي سَمْعِي", "Allahumma 'afini fi sam'i"),
        DuaData("For Sight", "اللَّهُمَّ عَافِنِي فِي بَصَرِي", "Allahumma 'afini fi basari"),
        DuaData("Against Debt", "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْبُخْلِ وَالْهَرَمِ", "Allahumma inni a'udhu bika minal-bukhli wal-haram"),
        DuaData("Seeking Paradise", "اللَّهُمَّ إِنِّي أَسْأَلُكَ الْجَنَّةَ", "Allahumma inni as'alukal-jannah"),
        DuaData("Yunus (Relief)", "لَا إِلَهَ إِلَّا أَنْتَ سُبْحَانَكَ إِنِّي كُنْتُ مِنَ الظَّالِمِينَ", "La ilaha illa Anta subhanaka inni kuntu minaz-zalimin"),
        DuaData("Entering Market", "لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ", "La ilaha illallahu wahdahu la sharika lah"),
        DuaData("Dua for Travel", "سُبْحَانَ الَّذِي سَخَّرَ لَنَا هَذَا", "Subhanal-ladhi sakhara lana hadha"),
        DuaData("Returning from Travel", "آيِبُونَ تَائِبُونَ عَابِدُونَ لِرَبِّنَا حَامِدُونَ", "Ayibuna ta'ibuna 'abiduna lirabbina hamidun"),
        DuaData("When Raining", "اللَّهُمَّ صَيِّبًا نَافِعًا", "Allahumma sayyiban nafi'an"),
        DuaData("After Rain", "مُطِرْنَا بِفَضْلِ اللَّهِ وَرَحْمَتِهِ", "Mutirna bifadlillahi wa rahmatihi"),
        DuaData("For Forgiveness", "رَبَّنَا ظَلَمْنَا أَنْفُسَنَا وَإِنْ لَمْ تَغْفِرْ لَنَا", "Rabbana zalamna anfusana wa in lam taghfir lana"),
        DuaData("For Patience", "رَبَّنَا أَفْرِغْ عَلَيْنَا صَبْرًا", "Rabbana afrigh 'alayna sabran"),
        DuaData("Against Laziness", "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْعَجْزِ وَالْكَسَلِ", "Allahumma inni a'udhu bika minal-'ajzi wal-kasal"),
        DuaData("Entering Toilet", "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْخُبُثِ وَالْخَبَائِثِ", "Allahumma inni a'udhu bika minal-khubuthi wal-khaba'ith"),
        DuaData("Leaving Toilet", "غُفْرَانَكَ", "Ghufranaka"),
        DuaData("Facing Enemies", "اللَّهُمَّ إِنَّا نَجْعَلُكَ فِي نُحُورِهِمْ", "Allahumma inna naj'aluka fi nuhurihim"),
        DuaData("Thanking People", "جَزَاكَ اللَّهُ خَيْرًا", "Jazakallahu khayran"),
        DuaData("Wearing Clothes", "الْحَمْدُ لِلَّهِ الَّذِي كَسَانِي هَذَا", "Alhamdu lillahil-ladhi kasani hadha"),
        DuaData("After Sneezing", "الْحَمْدُ لِلَّهِ", "Alhamdu lillah"),
        DuaData("Seeking Love of Allah", "اللَّهُمَّ إِنِّي أَسْأَلُكَ حُبَّكَ", "Allahumma inni as'aluka hubbak")
    )

    LazyColumn(Modifier.fillMaxSize().padding(20.dp)) {
        item { Text("Ramadan Duas", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = EmeraldDark); Spacer(Modifier.height(20.dp)) }
        items(duas) { d ->
            Card(Modifier.fillMaxWidth().padding(bottom = 15.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFF5F5F5))) {
                Column(Modifier.padding(20.dp)) {
                    Text(d.title, fontWeight = FontWeight.Bold, color = GoldPremium)
                    Text(d.arabic, fontSize = 22.sp, modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(), textAlign = TextAlign.Right, color = EmeraldDark)
                    Text(d.pron, color = Color.Gray, fontSize = 13.sp, fontStyle = FontStyle.Italic)
                }
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

data class DuaData(val title: String, val arabic: String, val pron: String)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Developer Info", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = EmeraldDark)
        Spacer(Modifier.height(25.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp)) {
                InfoTile("Name", "Khant", Icons.Default.Person)
                InfoTile("Email", "retriling123@gmail.com", Icons.Default.Email)
                InfoTile("Phone", "+95 9969210034", Icons.Default.Phone)
                InfoTile("Telegram", "@Retriling", Icons.Default.Send)
            }
        }

        Spacer(Modifier.height(20.dp))

        Text("Social & Source", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = EmeraldDark)
        Spacer(Modifier.height(10.dp))

        SocialButton("Facebook", "https://www.facebook.com/Retriling", EmeraldDark)
        SocialButton("GitHub", "https://github.com/retriling-111", Color.Black)
        SocialButton("LinkedIn", "https://www.linkedin.com/in/khant-dev", Color(0xFF0077B5))

        Spacer(Modifier.height(30.dp))
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Download Ramadan 2026 App by Khant: https://github.com/retriling-111/Ramadan-App")
                }
                context.startActivity(Intent.createChooser(intent, "Share"))
            },
            modifier = Modifier.fillMaxWidth().height(55.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GoldPremium),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Share, null, tint = EmeraldDark)
            Text(" Share Official App", fontWeight = FontWeight.Bold, color = EmeraldDark)
        }
    }
}

@Composable
fun SocialButton(label: String, url: String, color: Color) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(0.3f))
    ) {
        Text(label, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InfoTile(l: String, v: String, i: ImageVector) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(i, null, tint = GoldPremium, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 15.dp)) {
            Text(l, fontSize = 11.sp, color = Color.Gray)
            Text(v, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = EmeraldDark)
        }
    }
}

@Composable
fun FloatingBottomNav(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Surface(
        modifier = Modifier.padding(20.dp).fillMaxWidth().height(65.dp),
        color = EmeraldDark,
        shape = RoundedCornerShape(35.dp),
        shadowElevation = 15.dp
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            val navItems = listOf("home" to Icons.Default.Home, "duas" to Icons.AutoMirrored.Filled.MenuBook, "settings" to Icons.Default.Person)
            navItems.forEach { (route, icon) ->
                IconButton(onClick = { navController.navigate(route) }) {
                    Icon(icon, null, tint = if (currentRoute == route) GoldPremium else Color.White.copy(0.4f), modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable
fun TasbeehScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tasbeeh_prefs", Context.MODE_PRIVATE) }
    var count by remember { mutableIntStateOf(prefs.getInt("count", 0)) }

    Column(Modifier.fillMaxSize().background(SoftCanvas), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Dhikr Counter", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
        Text("$count", fontSize = 100.sp, fontWeight = FontWeight.ExtraBold, color = EmeraldDark)
        Spacer(Modifier.height(40.dp))
        Box(
            modifier = Modifier.size(220.dp).shadow(20.dp, CircleShape).background(EmeraldDark, CircleShape).clickable {
                count++
                prefs.edit { putInt("count", count) }
            },
            contentAlignment = Alignment.Center
        ) {
            Text("TAP", color = GoldPremium, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { count = 0; prefs.edit { putInt("count", 0) } }, Modifier.padding(top = 30.dp)) {
            Text("Reset Counter", color = Color.Red.copy(0.7f), fontWeight = FontWeight.Bold)
        }
    }
}
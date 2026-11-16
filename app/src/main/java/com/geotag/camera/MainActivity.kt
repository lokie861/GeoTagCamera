package com.geotag.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.appcompat.app.AlertDialog
import android.util.Size
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

data class GeoInfo(
    val lat: Double,
    val lon: Double,
    val address: String,
    val map: Bitmap?,
    val wind: String,
    val pressure: String,
    val humidity: String,
    val temp: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var ivMap: ImageView
    private lateinit var tvAddress: TextView
    private lateinit var tvCoords: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvWind: TextView
    private lateinit var tvHum: TextView

    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // label -> target size (landscape w x h, 4:3). null = highest available.
    private val resOptions: List<Pair<String, Size?>> = listOf(
        "Max (highest available)" to null,
        "12 MP (4000 x 3000)" to Size(4000, 3000),
        "8 MP (3264 x 2448)" to Size(3264, 2448),
        "5 MP (2592 x 1944)" to Size(2592, 1944),
        "2 MP (1600 x 1200)" to Size(1600, 1200),
        "1 MP (1280 x 960)" to Size(1280, 960),
        "VGA (640 x 480)" to Size(640, 480)
    )
    private var selectedRes = 0

    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null
    private var lastFetchLoc: Location? = null
    private var lastFetchTime = 0L
    @Volatile private var geo: GeoInfo? = null

    private val netExecutor = Executors.newSingleThreadExecutor()
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    private val clockTick = object : Runnable {
        override fun run() {
            tvTime.text = formatDateTime(System.currentTimeMillis())
            handler.postDelayed(this, 1000)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            tvCoords.text = formatCoords(location.latitude, location.longitude)
            refreshGeo(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) startCamera()
            else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            if (hasLocationPermission()) startLocation()
            else Toast.makeText(this, "Location permission is required for geo tags", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        ivMap = findViewById(R.id.ivMap)
        tvAddress = findViewById(R.id.tvAddress)
        tvCoords = findViewById(R.id.tvCoords)
        tvTime = findViewById(R.id.tvTime)
        tvWind = findViewById(R.id.tvWind)
        tvHum = findViewById(R.id.tvHum)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        findViewById<View>(R.id.btnCapture).setOnClickListener { takePhoto() }
        val btnRes = findViewById<TextView>(R.id.btnRes)
        btnRes.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Photo resolution")
                .setSingleChoiceItems(resOptions.map { it.first }.toTypedArray(), selectedRes) { d, which ->
                    selectedRes = which
                    btnRes.text = resOptions[which].first.substringBefore(" (")
                    d.dismiss()
                    startCamera()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<ImageButton>(R.id.btnSwitch).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
            startLocation()
        } else {
            permissionLauncher.launch(needed)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        netExecutor.shutdown()
        saveExecutor.shutdown()
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val target = resOptions[selectedRes].second
            val strategy = if (target == null) ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
            else ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(strategy)
                .build()
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(selector)
                .build()
            imageCapture = capture
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    preview,
                    capture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val loc = lastLocation
        if (loc == null) {
            Toast.makeText(this, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show()
            return
        }
        // Snapshot geo data at the moment of the shutter press.
        val info = geo ?: GeoInfo(
            loc.latitude, loc.longitude,
            "%.6f, %.6f".format(Locale.US, loc.latitude, loc.longitude),
            null, "--", "--", "--", "--"
        )
        val timestamp = System.currentTimeMillis()

        capture.takePicture(saveExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    image.close()
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    val stamped = drawOverlay(rotated, info, timestamp)
                    saveToGallery(stamped, info, timestamp)
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Saved to Pictures/GeoTagCamera", Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    // ---------------------------------------------------------------- location

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPermission()) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 2000L, 5f, locationListener)
                    locationManager.getLastKnownLocation(provider)?.let {
                        if (lastLocation == null) locationListener.onLocationChanged(it)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun refreshGeo(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        val prev = lastFetchLoc
        if (prev != null && loc.distanceTo(prev) < 30f && now - lastFetchTime < 120_000) return
        if (!fetching.compareAndSet(false, true)) return
        netExecutor.execute {
            try {
                val info = buildGeoInfo(loc)
                lastFetchLoc = loc
                lastFetchTime = now
                runOnUiThread {
                    geo = info
                    renderLive(info)
                }
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun renderLive(info: GeoInfo) {
        tvAddress.text = info.address
        tvCoords.text = formatCoords(info.lat, info.lon)
        tvWind.text = "Wind ${info.wind}   Pressure ${info.pressure}"
        tvHum.text = "Humidity ${info.humidity}   Temp ${info.temp}"
        info.map?.let { ivMap.setImageBitmap(it) }
    }

    // ------------------------------------------------------- network lookups

    private fun buildGeoInfo(loc: Location): GeoInfo {
        val lat = loc.latitude
        val lon = loc.longitude

        var address = "%.6f, %.6f".format(Locale.US, lat, lon)
        try {
            @Suppress("DEPRECATION")
            val list = Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)
            val line = list?.firstOrNull()?.getAddressLine(0)
            if (!line.isNullOrBlank()) address = line
        } catch (_: Exception) {}

        var wind = "--"
        var pressure = "--"
        var humidity = "--"
        var temp = "--"
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,surface_pressure,wind_speed_10m,wind_direction_10m" +
                "&wind_speed_unit=kmh"
            val cur = JSONObject(httpGetString(url)).getJSONObject("current")
            val dir = compass(cur.getDouble("wind_direction_10m"))
            wind = "%.0f km/h %s".format(Locale.US, cur.getDouble("wind_speed_10m"), dir)
            pressure = "%.0f hPa".format(Locale.US, cur.getDouble("surface_pressure"))
            humidity = "%.0f %%".format(Locale.US, cur.getDouble("relative_humidity_2m"))
            temp = "%.0f°C".format(Locale.US, cur.getDouble("temperature_2m"))
        } catch (_: Exception) {}

        val map = (try { fetchGoogleMap(lat, lon) } catch (_: Exception) { null })
            ?: (try { fetchMap(lat, lon) } catch (_: Exception) { null })
        return GeoInfo(lat, lon, address, map, wind, pressure, humidity, temp)
    }

    private fun httpGetString(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "GeoTagCamera/1.0")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun httpGetBitmap(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "GeoTagCamera/1.0")
        return try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
    }

    /** Google Static Maps image (800x800) with an orange marker at the location. Needs MAPS_API_KEY. */
    private fun fetchGoogleMap(lat: Double, lon: Double): Bitmap? {
        val key = BuildConfig.MAPS_API_KEY
        if (key.isBlank()) return null
        val url = "https://maps.googleapis.com/maps/api/staticmap?center=$lat,$lon&zoom=17" +
            "&size=400x400&scale=2&maptype=roadmap" +
            "&markers=color:orange%7C$lat,$lon&key=$key"
        return httpGetBitmap(url)
    }

    /** Fallback: fetches 2x2 OpenStreetMap tiles around the point and crops a 256px square centred on it, pin included. */
    private fun fetchMap(lat: Double, lon: Double): Bitmap? {
        val z = 16
        val n = 2.0.pow(z)
        val latRad = Math.toRadians(lat)
        val xf = (lon + 180.0) / 360.0 * n
        val yf = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        val tx = floor(xf).toInt()
        val ty = floor(yf).toInt()
        val px = ((xf - tx) * 256).toFloat()
        val py = ((yf - ty) * 256).toFloat()
        val tx0 = if (px < 128f) tx - 1 else tx
        val ty0 = if (py < 128f) ty - 1 else ty

        val composite = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val cv = Canvas(composite)
        cv.drawColor(Color.LTGRAY)
        var any = false
        for (dx in 0..1) for (dy in 0..1) {
            val tile = httpGetBitmap("https://tile.openstreetmap.org/$z/${tx0 + dx}/${ty0 + dy}.png")
            if (tile != null) {
                cv.drawBitmap(tile, dx * 256f, dy * 256f, null)
                any = true
            }
        }
        if (!any) return null

        val pinX = (xf - tx0) * 256
        val pinY = (yf - ty0) * 256
        val left = (pinX - 128).toInt().coerceIn(0, 256)
        val top = (pinY - 128).toInt().coerceIn(0, 256)
        val crop = Bitmap.createBitmap(composite, left, top, 256, 256)
        val cx = (pinX - left).toFloat()
        val cy = (pinY - top).toFloat()

        val pc = Canvas(crop)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.BLACK
        val tri = Path().apply {
            moveTo(cx - 12f, cy - 18f); lineTo(cx + 12f, cy - 18f); lineTo(cx, cy); close()
        }
        pc.drawPath(tri, p)
        pc.drawCircle(cx, cy - 28f, 18f, p)
        p.color = Color.parseColor("#FFA000")
        pc.drawCircle(cx, cy - 28f, 9f, p)
        return crop
    }

    // ----------------------------------------------------------- overlay draw

    private fun drawOverlay(src: Bitmap, info: GeoInfo, timeMs: Long): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val s = min(bmp.width, bmp.height) / 1080f

        val margin = 40f * s
        val pad = 24f * s
        val mapSize = 250f * s
        val cardW = min(bmp.width - 2 * margin, 1000f * s)
        val left = margin
        val textX = left + pad + mapSize + pad
        val textW = (left + cardW - pad - textX).toInt()

        val addrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 32f * s; typeface = Typeface.DEFAULT
        }
        val linePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 30f * s; typeface = Typeface.DEFAULT
        }
        val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 30f * s; typeface = Typeface.DEFAULT_BOLD
        }

        val addrLayout = StaticLayout.Builder
            .obtain(info.address, 0, info.address.length, addrPaint, textW)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

        val lineH = 42f * s
        val textHeight = addrLayout.height + lineH * 4
        val cardH = maxOf(mapSize, textHeight) + 2 * pad
        val bottom = bmp.height - margin
        val top = bottom - cardH

        // card background
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(179, 0, 0, 0) }
        c.drawRoundRect(RectF(left, top, left + cardW, bottom), 28f * s, 28f * s, bg)

        // map thumbnail
        val mapRect = RectF(left + pad, top + pad, left + pad + mapSize, top + pad + mapSize)
        c.save()
        c.clipPath(Path().apply { addRoundRect(mapRect, 20f * s, 20f * s, Path.Direction.CW) })
        if (info.map != null) {
            c.drawBitmap(info.map, null, mapRect, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            c.drawRect(mapRect, Paint().apply { color = Color.DKGRAY })
        }
        c.restore()

        // text block
        var y = top + pad
        c.save()
        c.translate(textX, y)
        addrLayout.draw(c)
        c.restore()
        y += addrLayout.height

        fun line(text: String, paint: Paint = linePaint) {
            y += lineH
            c.drawText(text, textX, y - 10f * s, paint)
        }

        line("Lat : %.6f   Long : %.6f".format(Locale.US, info.lat, info.lon))
        line(formatDateTime(timeMs))
        line("Wind ${info.wind}   Pressure ${info.pressure}")
        line("Humidity ${info.humidity}   Temp ${info.temp}")
        return bmp
    }

    // ------------------------------------------------------------------- save

    private fun saveToGallery(bmp: Bitmap, info: GeoInfo, timeMs: Long) {
        val name = "GEO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timeMs)) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GeoTagCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create file")
        contentResolver.openOutputStream(uri)!!.use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        // Embed GPS coordinates in EXIF as well.
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setLatLong(info.lat, info.lon)
                exif.saveAttributes()
            }
        } catch (_: Exception) {}

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
    }

    // ---------------------------------------------------------------- helpers

    private fun formatCoords(lat: Double, lon: Double) =
        "Lat : %.6f   Long : %.6f".format(Locale.US, lat, lon)

    private fun formatDateTime(ms: Long): String {
        val date = SimpleDateFormat("dd-MM-yyyy   hh:mm a", Locale.US).format(Date(ms))
        val offMin = TimeZone.getDefault().getOffset(ms) / 60000
        val sign = if (offMin < 0) "-" else "+"
        val a = abs(offMin)
        return "%s   GMT %s%02d:%02d".format(Locale.US, date, sign, a / 60, a % 60)
    }

    private fun compass(deg: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[(((deg + 22.5) % 360) / 45).toInt() % 8]
    }
}

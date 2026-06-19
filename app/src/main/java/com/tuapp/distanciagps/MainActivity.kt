package com.tuapp.distanciagps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var tvMeters: TextView
    private lateinit var tvKm: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var btnReset: Button
    private lateinit var btnCalibrate: Button
    private lateinit var prefs: android.content.SharedPreferences

    private var totalMeters = 0.0
    private var lastLocation: Location? = null
    private var calibrationFactor = 1.0

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handleNewLocation(location)
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { tvStatus.text = "GPS activo" }
        override fun onProviderDisabled(provider: String) {
            tvStatus.text = "GPS desactivado — activalo en Ajustes"
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startTracking() else tvStatus.text = "Permiso de ubicación denegado" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        prefs = getSharedPreferences("odometro_prefs", Context.MODE_PRIVATE)
        calibrationFactor = prefs.getFloat("calibration_factor", 1.0f).toDouble()

        tvMeters = findViewById(R.id.tvMeters)
        tvKm = findViewById(R.id.tvKm)
        tvStatus = findViewById(R.id.tvStatus)
        tvAccuracy = findViewById(R.id.tvAccuracy)
        btnReset = findViewById(R.id.btnReset)
        btnCalibrate = findViewById(R.id.btnCalibrate)

        renderDistance()
        updateCalibrationLabel()

        btnReset.setOnClickListener {
            totalMeters = 0.0
            lastLocation = null
            renderDistance()
        }

        btnCalibrate.setOnClickListener { showCalibrationDialog() }

        checkPermissionAndStart()
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startTracking()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startTracking() {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            tvStatus.text = "Activá el GPS del celular"
            return
        }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener
            )
            tvStatus.text = "Buscando señal…"
        } catch (e: SecurityException) {
            tvStatus.text = "Permiso de ubicación denegado"
        }
    }

    private fun handleNewLocation(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy else 50f
        renderAccuracy(accuracy)

        val previous = lastLocation
        if (previous != null) {
            val rawDistance = previous.distanceTo(location).toDouble()
            val noiseFloor = max(3.0, accuracy * 0.6)
            if (rawDistance >= noiseFloor) {
                totalMeters += rawDistance * calibrationFactor
                lastLocation = location
                renderDistance()
            }
        } else {
            lastLocation = location
        }
    }

    private fun renderDistance() {
        tvMeters.text = String.format(Locale("es", "AR"), "%,.0f", totalMeters)
        tvKm.text = String.format(Locale("es", "AR"), "%.2f km", totalMeters / 1000.0)
    }

    private fun renderAccuracy(accuracy: Float) {
        tvAccuracy.text = "Precisión: ± ${accuracy.roundToInt()} m"
        tvStatus.text = when {
            accuracy <= 12 -> "GPS activo"
            accuracy <= 35 -> "Señal débil"
            else -> "Señal pobre"
        }
    }

    private fun updateCalibrationLabel() {
        btnCalibrate.text = String.format(Locale("es", "AR"), "Calibrar (%.1f%%)", calibrationFactor * 100.0)
    }

    private fun showCalibrationDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_calibration, null)
        val etApp = view.findViewById<EditText>(R.id.etAppDistance)
        val etReal = view.findViewById<EditText>(R.id.etRealDistance)

        AlertDialog.Builder(this)
            .setTitle("Calibrar con el odómetro del auto")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                val appVal = etApp.text.toString().replace(",", ".").toDoubleOrNull()
                val realVal = etReal.text.toString().replace(",", ".").toDoubleOrNull()
                if (appVal != null && appVal > 0 && realVal != null && realVal > 0) {
                    calibrationFactor = realVal / appVal
                    prefs.edit().putFloat("calibration_factor", calibrationFactor.toFloat()).apply()
                    updateCalibrationLabel()
                }
            }
            .setNeutralButton("Restablecer (100%)") { _, _ ->
                calibrationFactor = 1.0
                prefs.edit().putFloat("calibration_factor", 1.0f).apply()
                updateCalibrationLabel()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(locationListener)
    }
}

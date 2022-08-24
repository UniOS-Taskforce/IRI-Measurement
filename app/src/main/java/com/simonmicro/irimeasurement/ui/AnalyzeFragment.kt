package com.simonmicro.irimeasurement.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.simonmicro.irimeasurement.BuildConfig
import com.simonmicro.irimeasurement.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView

class AnalyzeFragment : Fragment() {
    private val REQUEST_PERMISSIONS_REQUEST_CODE = 1
    private var userWantsToGoToTheMap = false
    private var permissionTranslations: Map<String, String>? = null
    private var map: MapView? = null

    fun getUserLocation(that: AppCompatActivity, constraintLayout: ConstraintLayout?): DoubleArray? {
        if (ActivityCompat.checkSelfPermission(that, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(that, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            Snackbar.make(constraintLayout!!, "Permission for location is missing", Snackbar.LENGTH_LONG).show()
            return null
        }

        // Get the current user position (if permission is granted)
        val mLocationManager = that.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (mLocationManager != null) {
            val locations = mLocationManager.getProviders(true)
            if (locations.size > 0) {
                val location = mLocationManager.getLastKnownLocation(locations[0])
                if (location != null) {
                    return doubleArrayOf(location.latitude, location.longitude)
                } else Snackbar.make(constraintLayout!!, "Location currently unavailable", Snackbar.LENGTH_LONG).show()
            } else Snackbar.make(constraintLayout!!, "No location provider available", Snackbar.LENGTH_LONG).show()
        } else Snackbar.make(constraintLayout!!, "Location service unavailable", Snackbar.LENGTH_LONG).show()
        return null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionsToRequest = ArrayList<String>()
        val permissionsToRequestTranslations = ArrayList<String?>()
        var showExplanation = false
        for (i in grantResults.indices) if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this.requireActivity(), permissions[i]))
                showExplanation = true
            permissionsToRequest.add(permissions[i])
            permissionsToRequestTranslations.add(permissionTranslations!![permissions[i]])
        }

        // Show an alert
        if (showExplanation) {
            val builder = AlertDialog.Builder(this.requireActivity())
            if (permissionsToRequest.size > 0) {
                builder.setTitle("Permission" + (if (permissionsToRequest.size > 1) "s" else "") + " required!")
                    .setMessage(
                        """
                    As you may already have noted, using an GEO-application will require using your precise location! Currently missing permissions are:
                    - ${java.lang.String.join("\n- ", permissionsToRequestTranslations)}
                    
                    Please grant them - otherwise the app may won't proceed...
                    """.trimIndent()
                    )
            } else {
                builder.setTitle("Permission" + (if (permissionsToRequest.size > 1) "s" else "") + " granted!")
                    .setMessage("The permissions are granted now - thank you! Please retry your action now...")
                //if (this.userWantsToGoToTheMap)
                //    this.openMap();
            }
            val alert = builder.create()
            alert.show()
        } // else if (this.userWantsToGoToTheMap)
        //    this.openMap();
    }

    private fun requestPermissionsIfNecessary(permissions: Array<String>): Boolean {
        userWantsToGoToTheMap =
            false // Reset this intend by default, as this flag will be set AFTER this functions return.
        val permissionsToRequest = ArrayList<String>()
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this.requireContext(), permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // Permission is not granted
                permissionsToRequest.add(permission)
            }
        }

        // Try to get them!
        if (permissionsToRequest.size > 0) {
            ActivityCompat.requestPermissions(this.requireActivity(), permissionsToRequest.toTypedArray(), REQUEST_PERMISSIONS_REQUEST_CODE)
            return false
        }
        return true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        var view: View = inflater.inflate(R.layout.fragment_analyze, container, false)

        // Init valid UserAgent for the map (otherwise tiles won't load)
        val s = BuildConfig.APPLICATION_ID + "@" + BuildConfig.VERSION_NAME
        println("Using agent: '$s'")
        Configuration.getInstance().userAgentValue = s

        // Init the map
        map = view.findViewById<MapView>(R.id.map)
        map!!.setTileSource(TileSourceFactory.MAPNIK)
        map!!.setMultiTouchControls(true)

        permissionTranslations = java.util.Map.of(
            Manifest.permission.ACCESS_COARSE_LOCATION, "Coarse Location",
            Manifest.permission.ACCESS_FINE_LOCATION, "Fine Location"
        )

        //Button startBtn = findViewById(R.id.calcButton);
        //ImageButton mapButton = findViewById(R.id.mapButton);
        val locationPermissions = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        this.requestPermissionsIfNecessary(locationPermissions)
        return view
    }
}
package com.simonmicro.irimeasurement

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import java.util.logging.Logger


class LocationService {
    private val REQUEST_PERMISSIONS_REQUEST_CODE = 1
    private val log = Logger.getLogger(LocationService::class.java.name)
    private val context: Context
    var showWarning: Boolean

    companion object {
        var snackbarTarget: View? = null
    }

    constructor(context: Context, showWarnings: Boolean) {
        this.context = context
        this.showWarning = showWarnings
    }

    @SuppressLint("MissingPermission")
    fun getUserLocation(): DoubleArray? {
        if(!this.hasLocationPermissions())
            return null

        // Get the current user position (if permission is granted)
        val locationManager = this.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = locationManager.getBestProvider(Criteria(), true)
        if (provider != null) {
            val location = locationManager.getLastKnownLocation(provider)
            if (location != null)
                return doubleArrayOf(location.latitude, location.longitude)
            this.showWarning("Location currently unavailable...")
        } else
            this.showWarning("No location provider available?!")

        return null
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(looper: Looper, listener: LocationListener) {
        if(!this.hasLocationPermissions())
            return

        val locationManager = this.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = locationManager.getBestProvider(Criteria(), true)
        if (provider != null) {
            locationManager.requestLocationUpdates(provider, 0, 0.0f, listener, looper)
        } else
            this.showWarning("No location provider available?!")
    }

    fun stopLocationUpdates(listener: LocationListener) {
        if(!this.hasLocationPermissions())
            return

        val locationManager = this.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = locationManager.getBestProvider(Criteria(), true)
        if (provider != null) {
            locationManager.removeUpdates(listener)
        } else
            this.showWarning("No location provider available?!")
    }

    private fun showWarning(msg: String) {
        this.log.info(msg)
        if(!this.showWarning)
            return
        if(LocationService.snackbarTarget != null) {
            val snackBar = Snackbar.make(LocationService.snackbarTarget!!, msg, Snackbar.LENGTH_INDEFINITE)
            snackBar.duration = msg.length * (1000 / 4)
            snackBar.setAction("Dismiss") {
                snackBar.dismiss()
            }
            snackBar.show()
        }
    }

    fun hasLocationPermissions(requirePrecise: Boolean = false): Boolean {
        if (ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            (!requirePrecise && ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
             return true
        } else {
            this.showWarning("A permission for location is missing. The requested function is may not available.")
            return false
        }
    }

    /**
     * This is an alternative to the "hasLocationPermissions()" function, which will also try to get
     * the needed permissions, if necessary
     */
    fun requestPermissionsIfNecessary(activity: Activity): Boolean {
        val permissions: Array<String> = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        val permissionsToRequest = ArrayList<String>()
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this.context, permission) != PackageManager.PERMISSION_GRANTED) {
                // Permission is not granted
                permissionsToRequest.add(permission)
            }
        }

        // Try to get them!
        if (permissionsToRequest.size > 0) {
            this.log.warning("Huh?")
            ActivityCompat.requestPermissions(activity, permissionsToRequest.toTypedArray(), REQUEST_PERMISSIONS_REQUEST_CODE)
            return false
        }
        return true
    }

    /*
     * Please make sure to forward the callback to your activity back to here!
     */
    fun onRequestPermissionsResult(activity: Activity, requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if(requestCode != REQUEST_PERMISSIONS_REQUEST_CODE)
            return
        this.log.warning("Heyho!")
        val permissionsToRequest = ArrayList<String>()
        var showExplanation = false
        for (i in grantResults.indices) if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permissions[i]))
                showExplanation = true
            permissionsToRequest.add(permissions[i])
        }

        // Show an alert
        if (showExplanation) {
            val builder = AlertDialog.Builder(this.context)
            builder.setTitle("Permission" + (if (permissionsToRequest.size > 1) "s" else "") + " required!")
                .setMessage("As you may already have noted, using an GEO-application will require using your precise location." +
                "Please grant its usage - otherwise the app may won't be able to proceed...")
            val alert = builder.create()
            alert.show()
        } else
            this.showWarning("A permission for location is missing. The requested function is may not available.")
    }
}
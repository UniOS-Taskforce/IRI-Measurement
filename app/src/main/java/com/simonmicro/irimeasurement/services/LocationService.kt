package com.simonmicro.irimeasurement.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.material.snackbar.Snackbar
import java.lang.Math.min

class LocationService(private val context: Context) {
    private val REQUEST_PERMISSIONS_REQUEST_CODE = 1

    companion object {
        private val log = com.simonmicro.irimeasurement.util.Log(LocationService::class.java.name)
        private lateinit var snackbarTarget: View
        private var fusedLocationClient: FusedLocationProviderClient? = null
        private var nativeManager: LocationManager? = null
        private var nativeProvider: String? = null
        private var locationTags = ArrayList<String>()

        fun initialize(activity: AppCompatActivity, snackbarTarget: View) {
            this.snackbarTarget = snackbarTarget
            val googlePlayStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity)
            if(googlePlayStatus != ConnectionResult.SUCCESS) {
                this.nativeManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var c = Criteria()
                c.isCostAllowed = true // At all costs ;)
                this.nativeProvider = this.nativeManager!!.getBestProvider(c, true)
                if (this.nativeProvider != null)
                    locationTags.add(this.nativeProvider!!)
                locationTags.add("native")
                this.showWarning("Google Play Services are not available (${this.serviceStatusToString(googlePlayStatus)}). Using native Android provider instead (${this.nativeProvider})...", true)
            } else {
                val builder = LocationSettingsRequest.Builder()

                val client: SettingsClient = LocationServices.getSettingsClient(activity)
                client.checkLocationSettings(builder.build()).addOnSuccessListener {
                    this.showWarning("Google Play services are used for location (GPS? ${it.locationSettingsStates?.isGpsUsable}, NET? ${it.locationSettingsStates?.isNetworkLocationUsable}, BLE? ${it.locationSettingsStates?.isBleUsable}).", true)
                    if(it.locationSettingsStates?.isGpsUsable == true)
                        locationTags.add("GPS")
                    if(it.locationSettingsStates?.isBleUsable == true)
                        locationTags.add("BLE")
                    if(it.locationSettingsStates?.isNetworkLocationUsable == true)
                        locationTags.add("NET")
                    locationTags.add("GLS")
                    fusedLocationClient = LocationServices.getFusedLocationProviderClient(activity)
                }.addOnFailureListener { exception ->
                    if (exception is ResolvableApiException){
                        // Location settings are not satisfied, but this can be fixed by showing the user a diathis.log.
                        try {
                            // Show the dialog by calling startResolutionForResult(),
                            // and check the result in onActivityResult().
                            exception.startResolutionForResult(activity, 1234)
                        } catch (sendEx: IntentSender.SendIntentException) {
                            // Ignore the error.
                            this.log.w(sendEx.stackTraceToString())
                        }
                    }
                }
            }
        }

        fun getLocationTags(): ArrayList<String> {
            return locationTags
        }

        private fun showWarning(msg: String, showSnack: Boolean) {
            log.w(msg)
            if(!showSnack)
                return
            val snackBar = Snackbar.make(snackbarTarget, msg, Snackbar.LENGTH_INDEFINITE)
            snackBar.duration = min(msg.length * (1000 / 4), 4 * 1000)
            snackBar.setAction("OK") {
                snackBar.dismiss()
            }
            val snackText: TextView = snackBar.view.findViewById(com.google.android.material.R.id.snackbar_text)
            snackText.maxLines = 4
            snackBar.show()
        }

        private fun serviceStatusToString(googlePlayStatus: Int): String {
            return if (googlePlayStatus == ConnectionResult.SUCCESS) {
                "SUCCESS"
            } else if (googlePlayStatus == ConnectionResult.SERVICE_MISSING)
                "SERVICE_MISSING"
            else if (googlePlayStatus == ConnectionResult.SERVICE_UPDATING)
                "SERVICE_UPDATING"
            else if (googlePlayStatus == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED)
                "SERVICE_VERSION_UPDATE_REQUIRED"
            else if (googlePlayStatus == ConnectionResult.SERVICE_DISABLED)
                "SERVICE_DISABLED"
            else if (googlePlayStatus == ConnectionResult.SERVICE_INVALID)
                "SERVICE_INVALID"
            else
                "Unknown status ($googlePlayStatus)"
        }
    }

    @SuppressLint("MissingPermission")
    fun getUserLocation(showWarning: Boolean = true): Task<Location>? {
        if(!this.hasLocationPermissions())
            return null
        
        if(fusedLocationClient != null) {
            // This code can be used to determine if we even could get a location...
            // fusedLocationClient!!.locationAvailability.addOnSuccessListener {
            //    this.log.d(${it.isLocationAvailable}")
            // }

            return fusedLocationClient!!.lastLocation
        } else {
            // Get the current user position (if permission is granted)
            if (nativeProvider != null) {
                val location = nativeManager?.getLastKnownLocation(nativeProvider!!)
                if (location != null)
                    return Tasks.forResult(location)
                showWarning("Location currently unavailable...", showWarning)
            } else
                showWarning("No location provider available?!", showWarning)
            return null
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(looper: Looper, lCb: LocationCallback, lLs: LocationListener): Boolean {
        if(!this.hasLocationPermissions())
            return false

        if(fusedLocationClient != null) {
            var lr: LocationRequest = LocationRequest.create()
            lr.priority = PRIORITY_HIGH_ACCURACY
            lr.smallestDisplacement = 0.0f
            var flc: FusedLocationProviderClient? = fusedLocationClient
            return if(flc != null) {
                flc.requestLocationUpdates(lr, lCb, looper)
                true
            } else
                false
        } else {
            return if (nativeProvider != null) {
                nativeManager?.requestLocationUpdates(nativeProvider!!, 0, 0.0f, lLs, looper)
                true
            } else {
                showWarning("Failed to register for location updates: No location provider available?!", true)
                false
            }
        }
    }

    fun stopLocationUpdates(lCb: LocationCallback, lLs: LocationListener): Boolean {
        if(!this.hasLocationPermissions())
            return false

        if(fusedLocationClient != null) {
            var flc: FusedLocationProviderClient? = fusedLocationClient
            return if(flc != null) {
                flc.removeLocationUpdates(lCb)
                true
            } else
                false
        } else {
            return if (nativeProvider != null) {
                nativeManager?.removeUpdates(lLs)
                true
            } else {
                showWarning("Failed to unregister for location updates: No location provider available?!", true)
                false
            }
        }
    }

    fun hasLocationPermissions(requirePrecise: Boolean = false, showWarning: Boolean = true): Boolean {
        return if (ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            (!requirePrecise && ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
            true
        } else {
            showWarning("A permission for location is or was missing. The requested function is may not available.", showWarning)
            false
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
            log.d("We are missing ${permissionsToRequest.size} permissions. Requesting...")
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
                .setMessage("As you may already have noted, using a GEO-application will require using your PRECISE location. " +
                "Please grant its usage (make sure to also allow precise location access!) - otherwise the app may won't be able to proceed...")
            val alert = builder.create()
            alert.show()
        } else
            showWarning("A permission for location is missing. The requested function is may not available.", true)
    }
}
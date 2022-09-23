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
import com.google.android.material.snackbar.Snackbar
import com.simonmicro.irimeasurement.RequestCodes
import java.lang.Math.min
import java.util.concurrent.TimeUnit

class LocationService(private val context: Context, activity: AppCompatActivity?) {
    private var glsClient: FusedLocationProviderClient? = null
    private var nativeManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    companion object {
        private val log = com.simonmicro.irimeasurement.util.Log(LocationService::class.java.name)
        private var showLocationInitMsg = true
        private var allKnownNativeProviders = mutableSetOf<String>()
        private var allGLSTags = mutableSetOf<String>()
        var snackbarTarget: View? = null // onDestroy() will remove that again :)

        private fun showWarning(msg: String, showSnack: Boolean) {
            log.w(msg)
            if(!showSnack || snackbarTarget == null)
                return
            val snackBar = Snackbar.make(snackbarTarget!!, msg, Snackbar.LENGTH_INDEFINITE)
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

    init {
        this.updateKnownNativeProviders()
        val googlePlayStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (googlePlayStatus != ConnectionResult.SUCCESS) {
            val builder = LocationSettingsRequest.Builder()
            val client: SettingsClient = LocationServices.getSettingsClient(context)
            client.checkLocationSettings(builder.build()).addOnSuccessListener {
                glsClient = LocationServices.getFusedLocationProviderClient(context)
                if (it.locationSettingsStates?.isGpsUsable == true)
                    allGLSTags.add("GPS")
                if (it.locationSettingsStates?.isBleUsable == true)
                    allGLSTags.add("BLE")
                if (it.locationSettingsStates?.isNetworkLocationUsable == true)
                    allGLSTags.add("NET")
            }.addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    // Location settings are not satisfied, but this can be fixed by showing the user a dialog.
                    try {
                        // Show the dialog by calling startResolutionForResult(),
                        // and check the result in onActivityResult().
                        if (activity != null)
                            exception.startResolutionForResult(activity, RequestCodes.REQUEST_GLS_SETTINGS_FIX) // The response itself it not really processed
                    } catch (sendEx: IntentSender.SendIntentException) {
                        // Ignore the error.
                        log.w(sendEx.stackTraceToString())
                    }
                }
            }
        }
        if(glsClient == null)
            showWarning("Google Play Services are not available (${serviceStatusToString(googlePlayStatus)}) - using native Android providers only (${allKnownNativeProviders.joinToString()}).", showLocationInitMsg)
        else
            showWarning("Google Play services are used for location (${allGLSTags.joinToString()}) in addition to native Android providers (${allKnownNativeProviders.joinToString()}).", showLocationInitMsg)
        if(snackbarTarget != null)
            showLocationInitMsg = false
    }

    private fun updateKnownNativeProviders() {
        val c = Criteria()
        c.isCostAllowed = true // At all costs ;)
        for(provider in this.nativeManager.getProviders(c, false))
            allKnownNativeProviders.add(provider)
    }

    fun getLocationTags(): Array<String> {
        val tags = mutableSetOf("native") // Native is always available
        tags.addAll(allKnownNativeProviders)
        if(glsClient != null) {
            tags.add("GLS")
            for(tag in allGLSTags)
                tags.add("GLS:$tag")
        }
        return tags.toTypedArray()
    }

    @SuppressLint("MissingPermission")
    fun getUserLocation(showWarning: Boolean = true): Location? {
        if(!this.hasLocationPermissions())
            return null
        this.updateKnownNativeProviders()

        val locationsToChooseFrom = ArrayList<Location>()

        // Fetch all native ones
        for(provider in allKnownNativeProviders) {
            val l = nativeManager.getLastKnownLocation(provider)
            if(l != null)
                locationsToChooseFrom.add(l)
        }

        // Fetch GLS
        if(glsClient != null) {
            val task = glsClient!!.lastLocation
            val timeout = 100
            for(i in 1..timeout) {
                if(task.isSuccessful) {
                    locationsToChooseFrom.add(task.result)
                    break
                } else if(i == timeout)
                    log.w("Timed out while waiting for location of the GLS...")
                TimeUnit.MILLISECONDS.sleep(100L)
            }
        }

        // Select the freshest location
        var returnMe: Location? = null
        for(loc in locationsToChooseFrom)
            if(returnMe == null || loc.time > returnMe.time)
                returnMe = loc

        if(returnMe == null)
            showWarning("Location currently unavailable...", showWarning)

        return returnMe
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(looper: Looper, lCb: LocationCallback, lLs: LocationListener): Boolean {
        if(!this.hasLocationPermissions())
            return false
        this.updateKnownNativeProviders()

        var registered = false

        // Register native ones
        for(provider in allKnownNativeProviders) {
            try {
                nativeManager.requestLocationUpdates(provider, 100, 0.0f, lLs, looper)
                registered = true
            } catch (e: Exception) {
                log.w("Failed to listen for location updates: $provider")
            }
        }

        // Register on GLS
        if(glsClient != null) {
            val lr: LocationRequest = LocationRequest.create()
            lr.smallestDisplacement = 0.0f
            lr.interval = 100
            glsClient!!.requestLocationUpdates(lr, lCb, looper)
            registered = true
        }

        if(!registered)
            showWarning("Failed to register for location updates: No location provider available?!", true)
        return registered
    }

    fun stopLocationUpdates(lCb: LocationCallback, lLs: LocationListener) {
        if(!this.hasLocationPermissions())
            return
        this.updateKnownNativeProviders()

        // Deregister native ones
        for(provider in allKnownNativeProviders) {
            try {
                nativeManager.removeUpdates(lLs)
            } catch (e: Exception) {
                log.w("Failed to listen for location updates: $provider")
            }
        }

        // Deregister on GLS
        glsClient?.removeLocationUpdates(lCb)
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
            ActivityCompat.requestPermissions(activity, permissionsToRequest.toTypedArray(), RequestCodes.REQUEST_PERMISSIONS_GRANT)
            return false
        }
        return true
    }

    /*
     * Please make sure to forward the callback to your activity back to here!
     */
    fun onRequestPermissionsResult(activity: Activity, requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if(requestCode != RequestCodes.REQUEST_PERMISSIONS_GRANT)
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
package com.simonmicro.irimeasurement.ui

import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.simonmicro.irimeasurement.*
import com.simonmicro.irimeasurement.services.LocationService
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.logging.Logger

class AnalyzeFragment : Fragment() {
    private var map: MapView? = null
    private val log = Logger.getLogger(LocationService::class.java.name)
    private var done: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        var view: View = inflater.inflate(R.layout.fragment_analyze, container, false)

        // Init valid UserAgent for the map (otherwise tiles won't load)
        val s = BuildConfig.APPLICATION_ID + "@" + BuildConfig.VERSION_NAME
        this.log.info("Using agent: '$s'")
        Configuration.getInstance().userAgentValue = s

        // Init the map
        map = view.findViewById<MapView>(R.id.map)
        map!!.setTileSource(TileSourceFactory.MAPNIK)
        map!!.setMultiTouchControls(true)

        if(HomeScreen.locService!!.requestPermissionsIfNecessary(this.requireActivity())) {
            // Oh, we already got all permissions? So, let's display the users location live on the map!
            this.done = false
            val that = this
            val handler = Handler()
            val runnableCode: Runnable = object : Runnable {
                override fun run() {
                    if(that.done) return
                    val loc: Location? = HomeScreen.locService!!.getUserLocation()
                    if(loc != null)
                        that.addMarker(loc.latitude, loc.longitude, true)
                    handler.postDelayed(this, 1000)
                }
            }
            handler.post(runnableCode)

            // Zoom into the map initially...
            val loc: Location? = HomeScreen.locService!!.getUserLocation()
            val zoom: Float = 0.02f
            if(loc != null) {
                map!!.addOnFirstLayoutListener { _: View?, _: Int, _: Int, _: Int, _: Int ->
                    val boundingBox = BoundingBox(
                        loc.latitude + zoom,
                        loc.longitude + zoom,
                        loc.latitude - zoom,
                        loc.longitude - zoom
                    )
                    map!!.zoomToBoundingBox(boundingBox, false, 100)
                    map!!.invalidate()
                }
            }
        }
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        this.done = true
    }

    fun addMarker(lat: Double, lon: Double, isPerson: Boolean): GeoPoint? {
        val p = GeoPoint(lat, lon)
        val m = Marker(map)
        if (isPerson) m.icon = resources.getDrawable(org.osmdroid.library.R.drawable.person)
        m.position = p
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        map!!.overlays.add(m)
        return p
    }
}
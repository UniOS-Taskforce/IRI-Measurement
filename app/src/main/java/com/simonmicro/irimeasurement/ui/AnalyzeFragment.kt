package com.simonmicro.irimeasurement.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.marginBottom
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.simonmicro.irimeasurement.*
import com.simonmicro.irimeasurement.services.LocationService
import com.simonmicro.irimeasurement.services.StorageService
import com.simonmicro.irimeasurement.services.points.LocationPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.lang.Double.max
import java.lang.Double.min
import java.util.*
import kotlin.collections.ArrayList

class AnalyzeFragment : Fragment() {
    private lateinit var mapShowSegmentMarkers: Switch
    private lateinit var mapShowIntermediateMarkers: Switch
    private lateinit var analysisUseAccelerometer: Switch
    private lateinit var analysisUseGeocoding: Switch
    private lateinit var map: MapView
    private lateinit var locService: LocationService
    private var mapExpanded: Boolean = true
    private val log = com.simonmicro.irimeasurement.util.Log(AnalyzeFragment::class.java.name)
    private var done: Boolean = false
    private var collectionOptions: ArrayList<String> = ArrayList()
    private lateinit var collectionsArrayAdapter: ArrayAdapter<String>
    private lateinit var analyzeNoCollection: TextView
    private lateinit var analyzeProperties: LinearLayout
    data class AnalyzeStatus(var working: Boolean, var workingProgress: Int = -1, var workingText: String = "", var resultText: String = "") { }
    var activeAnalysisThread: AnalysisThread? = null

    fun updateAnalyzeStatus(view: View, aStatus: AnalyzeStatus) {
        val pContainer: LinearLayout = view.findViewById(R.id.analyzeProgressContainer)
        val pBar: ProgressBar = view.findViewById(R.id.analyzeProgress)
        val pText: TextView = view.findViewById(R.id.analyzeProgressDetails)
        val dText: TextView = view.findViewById(R.id.analyzeDetails)
        if(aStatus.working) {
            pContainer.visibility = LinearLayout.VISIBLE
            if(aStatus.workingProgress != -1) {
                pBar.progress = aStatus.workingProgress
                pBar.isIndeterminate = false
            } else
                pBar.isIndeterminate = true
        } else
            pContainer.visibility = LinearLayout.GONE
        if(aStatus.working && aStatus.workingText.isNotEmpty()) {
            pText.visibility = TextView.VISIBLE
            pText.text = aStatus.workingText
        } else
            pText.visibility = TextView.GONE
        if(aStatus.resultText.isNotEmpty()) {
            dText.visibility = TextView.VISIBLE
            dText.text = aStatus.resultText
        } else
            dText.visibility = TextView.GONE
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view: View = inflater.inflate(R.layout.fragment_analyze, container, false)
        this.mapShowSegmentMarkers = view.findViewById(R.id.mapShowSegmentMarkers)
        this.mapShowIntermediateMarkers = view.findViewById(R.id.mapShowIntermediateMarkers)
        this.analysisUseAccelerometer = view.findViewById(R.id.analysisUseAccelerometer)
        this.analysisUseGeocoding = view.findViewById(R.id.analysisUseGeocoding)
        this.locService = LocationService(this.requireContext(), this.requireActivity() as AppCompatActivity)

        // Init valid UserAgent for the map (otherwise tiles won't load)
        val s = BuildConfig.APPLICATION_ID + "@" + BuildConfig.VERSION_NAME
        this.log.i("Using agent: '$s'")
        Configuration.getInstance().userAgentValue = s

        // Init the map
        map = view.findViewById<MapView>(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        val mapDefaultMargin: Int = map.marginBottom

        // Add map resize (with animation) to the button
        val resizeButtn: FloatingActionButton = view.findViewById<FloatingActionButton>(R.id.toggleLayoutButton)
        resizeButtn.setOnClickListener {
            this.mapExpanded = !this.mapExpanded

            val minHeight: Int = mapDefaultMargin
            val maxHeight: Int = view.measuredHeight - mapDefaultMargin
            val valueAnimator: ValueAnimator
            if(this.mapExpanded) {
                valueAnimator = ValueAnimator.ofInt(minHeight, maxHeight)
                resizeButtn.setImageResource(R.drawable.ic_twotone_arrow_upward_24)
            } else {
                valueAnimator = ValueAnimator.ofInt(maxHeight, minHeight)
                resizeButtn.setImageResource(R.drawable.ic_twotone_arrow_downward_24)
            }

            valueAnimator.duration = 500L
            valueAnimator.addUpdateListener {
                val animatedValue = valueAnimator.animatedValue as Int
                val params: ViewGroup.LayoutParams = map.layoutParams
                params.height = animatedValue
                map.layoutParams = params
            }
            valueAnimator.start()
        }

        val that = this
        if(this.locService.requestPermissionsIfNecessary(this.requireActivity())) {
            // Oh, we already got all permissions? So, let's display the users location live on the map!
            var lastUserLocation: Location? = null
            this.done = false
            val handler = Handler()
            var locationFailureCount = 0
            val runnableCode: Runnable = object : Runnable {
                override fun run() {
                    if(that.done) return
                    val loc = that.locService.getLastLocation(locationFailureCount == 0)
                    if(lastUserLocation == null || lastUserLocation != loc) {
                        log.d("Pushing current location to map: $loc")
                        if (loc != null && !that.done) { // Also respect done flag here, as this task may completes after the view switched
                            that.showUserLocation(loc.latitude, loc.longitude)
                            locationFailureCount = 0
                            if(lastUserLocation == null) // Only first time: Reset zoom
                                that.resetZoom(respectUserLocation = true, animated = false)
                        } else
                            locationFailureCount += 1
                    }
                    lastUserLocation = loc
                    handler.postDelayed(this, 1000)
                }
            }
            handler.post(runnableCode)
        }

        analyzeNoCollection = view.findViewById(R.id.analyzeNoCollection)
        analyzeProperties = view.findViewById(R.id.analyzeProperties)

        collectionsArrayAdapter = ArrayAdapter(view.context, R.layout.simple_spinner_item, collectionOptions)
        collectionsArrayAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)

        val spinner = view.findViewById<Spinner>(R.id.spinner)
        spinner.adapter = collectionsArrayAdapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parentView: AdapterView<*>?,
                selectedItemView: View?,
                position: Int,
                id: Long
            ) {
                that.startAnalysis(UUID.fromString(collectionOptions[position]))
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {
                // Ignore...
            }
        }
        this.initAnalyzeProperties()
        this.updateAnalyzeStatus(view, AnalyzeStatus(false))

        view.findViewById<Button>(R.id.triggerAnalysisNow).setOnClickListener {
            that.startAnalysis(null) // Hope, that is was executed before - so the UUID would be reusable
        }

        return view
    }

    private var lastAnalysisUUID: UUID? = null
    fun startAnalysis(uuid: UUID?) {
        val now: UUID? = uuid?: this.lastAnalysisUUID
        if(now != null) {
            this.activeAnalysisThread = AnalysisThread(requireView(), this, now, useAccelerometer = this.analysisUseAccelerometer.isChecked, useGeocoding = this.analysisUseGeocoding.isChecked)
            this.activeAnalysisThread!!.start()
            lastAnalysisUUID = now
        } else
            this.log.w("Can't restart analysis, as no UUID is set.")
    }

    override fun onResume() {
        super.onResume()
        this.initAnalyzeProperties()
    }

    private fun initAnalyzeProperties() {
        val l = StorageService.listCollections()
        if(l.size == 0) {
            analyzeNoCollection.visibility = TextView.VISIBLE
            analyzeProperties.visibility = LinearLayout.GONE
        } else {
            analyzeNoCollection.visibility = TextView.GONE
            analyzeProperties.visibility = LinearLayout.VISIBLE

            collectionOptions.clear()
            for(c in l)
                if(!c.isInUse())
                    collectionOptions.add(c.id.toString())
            collectionsArrayAdapter.notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        this.activeAnalysisThread = null // This should also terminate that one...
        this.done = true
    }

    private var userMarker: Marker? = null
    fun showUserLocation(lat: Double, lon: Double) {
        // Clear previous user marker
        if(userMarker == null) {
            this.userMarker = Marker(map)
            this.userMarker!!.icon = resources.getDrawable(org.osmdroid.library.R.drawable.person)
            this.userMarker!!.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            this.userMarker!!.title = "Your are here!"
            map.overlays.add(this.userMarker)
        }
        this.userMarker!!.position = GeoPoint(lat, lon)
        map.invalidate() // This forces the point to be visible NOW
    }

    private var otherMarkers = ArrayList<GeoPoint>()
    fun addSegmentMarker(location: LocationPoint) {
        if(!this.mapShowSegmentMarkers.isChecked) return
        val m = Marker(map)
        m.position = GeoPoint(location.locLat, location.locLon)
        otherMarkers.add(m.position)
        m.icon = resources.getDrawable(org.osmdroid.library.R.drawable.marker_default)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        map.overlays.add(m)
        map.invalidate() // This forces the points to be visible NOW
    }

    fun addIntermediateMarker(location: LocationPoint) {
        if(!this.mapShowIntermediateMarkers.isChecked) return
        val m = Marker(map)
        m.position = GeoPoint(location.locLat, location.locLon)
        otherMarkers.add(m.position)
        m.icon = resources.getDrawable(org.osmdroid.library.R.drawable.marker_default_focused_base)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        map.overlays.add(m)
    }

    private var lineColors = arrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN, Color.DKGRAY)
    private var lineColorsIndex: Int = 0
    fun addLineMarker(locations: List<LocationPoint>, title: String?) {
        val points = ArrayList<GeoPoint>()
        for(location in locations)
            points.add(GeoPoint(location.locLat, location.locLon))
        otherMarkers.addAll(points)
        val line = Polyline(map)
        line.setPoints(points)
        if(title != null)
            line.title = title
        line.color = lineColors[lineColorsIndex]
        lineColorsIndex += 1
        if(lineColorsIndex >= lineColors.size)
            lineColorsIndex = 0
        map.overlays.add(line)
        map.invalidate() // This forces the points to be visible NOW
    }

    fun clearMarkers() {
        map.overlays.removeAll {
            it != this.userMarker // Clear all except our user markers
        }
        otherMarkers.clear()
        map.invalidate() // This forces the points to be removed NOW
    }

    fun resetZoom(respectUserLocation: Boolean, animated: Boolean) {
        val border = 100
        val zoom = 0.02f
        var north = 0.0
        var east = 0.0
        var south = 0.0
        var west = 0.0
        // Init either with users location or a point
        if(respectUserLocation) {
            north = (this.userMarker?.position?.latitude ?: 0.0) + zoom
            east = (this.userMarker?.position?.longitude ?: 0.0) + zoom
            south = (this.userMarker?.position?.latitude ?: 0.0) - zoom
            west = (this.userMarker?.position?.longitude ?: 0.0) - zoom
        } else if(this.otherMarkers.size > 0) {
            north = this.otherMarkers[0].latitude
            east = this.otherMarkers[0].longitude
            south = this.otherMarkers[0].latitude
            west = this.otherMarkers[0].longitude
        }
        // Respect all points
        for(point in this.otherMarkers) {
            north = max(north, point.latitude)
            east = max(east, point.longitude)
            south = min(south, point.latitude)
            west = min(west, point.longitude)
        }
        val boundingBox = BoundingBox(
            north,
            east,
            south,
            west
        )
        // Queue update of the map
        CoroutineScope(Dispatchers.Main).launch {
            // In case the map was not rendered yet...
            map.addOnFirstLayoutListener { _: View?, _: Int, _: Int, _: Int, _: Int ->
                map.zoomToBoundingBox(boundingBox, animated, border)
                map.invalidate()
            }
            // In case the map is already visible...
            map.zoomToBoundingBox(boundingBox, animated, border)
            map.invalidate()
        }
    }
}
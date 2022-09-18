package com.simonmicro.irimeasurement.ui

import android.animation.ValueAnimator
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.marginBottom
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.simonmicro.irimeasurement.*
import com.simonmicro.irimeasurement.services.StorageService
import com.simonmicro.irimeasurement.Collection
import com.simonmicro.irimeasurement.services.IRICalculationService
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.*
import kotlin.collections.ArrayList

class AnalyzeFragment : Fragment() {
    private var map: MapView? = null
    private var mapExpanded: Boolean = true
    private val logTag = AnalyzeFragment::class.java.name
    private var done: Boolean = false
    private var collectionOptions: ArrayList<String> = ArrayList()
    private lateinit var collectionsArrayAdapter: ArrayAdapter<String>
    private lateinit var analyzeNoCollection: TextView
    private lateinit var analyzeProperties: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        var view: View = inflater.inflate(R.layout.fragment_analyze, container, false)

        // Init valid UserAgent for the map (otherwise tiles won't load)
        val s = BuildConfig.APPLICATION_ID + "@" + BuildConfig.VERSION_NAME
        Log.i(logTag, "Using agent: '$s'")
        Configuration.getInstance().userAgentValue = s

        // Init the map
        map = view.findViewById<MapView>(R.id.map)
        map!!.setTileSource(TileSourceFactory.MAPNIK)
        map!!.setMultiTouchControls(true)
        val mapDefaultMargin: Int = map!!.marginBottom

        // Add map resize (with animation) to the button
        var resizeButtn: FloatingActionButton = view.findViewById<FloatingActionButton>(R.id.toggleLayoutButton)
        resizeButtn.setOnClickListener {
            this.mapExpanded = !this.mapExpanded

            var minHeight: Int = mapDefaultMargin
            var maxHeight: Int = view.measuredHeight - mapDefaultMargin
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
                val params: ViewGroup.LayoutParams = map!!.layoutParams
                params.height = animatedValue
                map!!.layoutParams = params
            }
            valueAnimator.start()
        }

        val that = this
        if(HomeScreen.locService!!.requestPermissionsIfNecessary(this.requireActivity())) {
            // Oh, we already got all permissions? So, let's display the users location live on the map!
            this.done = false
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

        analyzeNoCollection = view.findViewById(R.id.analyzeNoCollection)
        analyzeProperties = view.findViewById(R.id.analyzeProperties)

        collectionsArrayAdapter = ArrayAdapter(view.context, R.layout.simple_spinner_item, collectionOptions)
        collectionsArrayAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)

        var spinner = view.findViewById<Spinner>(R.id.spinner)
        spinner.adapter = collectionsArrayAdapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parentView: AdapterView<*>?,
                selectedItemView: View?,
                position: Int,
                id: Long
            ) {
                try {
                    var c = Collection(UUID.fromString(collectionOptions[position]))
                    view.findViewById<TextView>(R.id.analyzeDetails).text = c.toSnackbarString()

                    // Analyze the data
                    var iriSvc = IRICalculationService(c)

                    // Determine the collected sections
                    var sections = iriSvc.getSectionRecommendations()
                    view.findViewById<TextView>(R.id.analyzeDynamicDetails).text =
                        "Sections: ${sections.size - 1}"

                    // Add a point for every section start and end
                    for (section in sections) {
                        var location = iriSvc.getLocation(section.start)
                        that.addMarker(location.locLat, location.locLon, false)
                        location = iriSvc.getLocation(section.end)
                        that.addMarker(location.locLat, location.locLon, false)
                        try {
                            Log.i(logTag, "IRI of section ${section}: ${iriSvc.getIRIValue(section)}")
                        } catch (e: Exception) {
                            Log.w(logTag, "Skipped section ${section}: ${e.message}")
                        }
                    }
                } catch(e: Exception) {
                    Log.e(logTag, e.stackTraceToString())
                    view.findViewById<TextView>(R.id.analyzeDynamicDetails).text =
                        "Failed to analyze the collection: ${e.message}"
                }
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {
                // Ignore...
            }
        }
        this.initAnalyzeProperties()

        return view
    }

    override fun onResume() {
        super.onResume()
        this.initAnalyzeProperties()
    }

    private fun initAnalyzeProperties() {
        var l = StorageService.listCollections()
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
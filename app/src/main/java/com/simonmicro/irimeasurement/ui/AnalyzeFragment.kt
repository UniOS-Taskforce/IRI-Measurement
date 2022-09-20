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
import com.google.android.gms.tasks.Task
import com.simonmicro.irimeasurement.*
import com.simonmicro.irimeasurement.services.StorageService
import com.simonmicro.irimeasurement.Collection
import com.simonmicro.irimeasurement.services.IRICalculationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    data class AnalyzeStatus(var working: Boolean, var workingProgress: Int = -1, var workingText: String = "", var resultText: String = "") { }
    var activeAnalysisThread: AnalysisThread? = null

    fun updateAnalyzeStatus(view: View, aStatus: AnalyzeStatus) {
        var pContainer: LinearLayout = view.findViewById(R.id.analyzeProgressContainer)
        var pBar: ProgressBar = view.findViewById(R.id.analyzeProgress)
        var pText: TextView = view.findViewById(R.id.analyzeProgressDetails)
        var dText: TextView = view.findViewById(R.id.analyzeDetails)
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
                    HomeScreen.locService!!.getUserLocation()?.addOnSuccessListener {
                        Log.d(logTag, "Pushing current location to map: $it")
                        if(it != null && !that.done) // Also respect done flag here, as this task may completes after the view switched
                            that.addMarker(it.latitude, it.longitude, true)
                    }?.addOnFailureListener {
                        Log.e(logTag, it.stackTraceToString())
                    }
                    handler.postDelayed(this, 1000)
                }
            }
            handler.post(runnableCode)

            // Zoom into the map initially...
            HomeScreen.locService!!.getUserLocation()?.addOnSuccessListener {
                Log.d(logTag, "Resetting zoom for location $it...")
                val zoom: Float = 0.02f
                if(it != null) {
                    val boundingBox = BoundingBox(
                        it.latitude + zoom,
                        it.longitude + zoom,
                        it.latitude - zoom,
                        it.longitude - zoom
                    )
                    // In case the map was not rendered yet...
                    map!!.addOnFirstLayoutListener { _: View?, _: Int, _: Int, _: Int, _: Int ->
                        map!!.zoomToBoundingBox(boundingBox, false, 100)
                        map!!.invalidate()
                    }
                    // In case the map is already visible...
                    map!!.zoomToBoundingBox(boundingBox, false, 100)
                    map!!.invalidate()
                }
            }?.addOnFailureListener {
                Log.e(logTag, it.stackTraceToString())
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
                that.activeAnalysisThread = AnalysisThread(view, that, UUID.fromString(collectionOptions[position]))
                that.activeAnalysisThread!!.start()
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {
                // Ignore...
            }
        }
        this.initAnalyzeProperties()
        this.updateAnalyzeStatus(view, AnalyzeStatus(false))

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
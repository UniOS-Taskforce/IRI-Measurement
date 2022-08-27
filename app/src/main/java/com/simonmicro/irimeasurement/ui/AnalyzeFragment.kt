package com.simonmicro.irimeasurement.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.simonmicro.irimeasurement.BuildConfig
import com.simonmicro.irimeasurement.HomeScreen
import com.simonmicro.irimeasurement.LocationService
import com.simonmicro.irimeasurement.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import java.util.logging.Logger

class AnalyzeFragment : Fragment() {
    private var map: MapView? = null
    private val log = Logger.getLogger(LocationService::class.java.name)

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

        HomeScreen.locService!!.requestPermissionsIfNecessary(this.requireActivity())
        return view
    }
}
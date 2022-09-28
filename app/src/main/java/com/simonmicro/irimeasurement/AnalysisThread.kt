package com.simonmicro.irimeasurement

import android.content.Context
import android.view.View
import com.simonmicro.irimeasurement.services.IRICalculationService
import com.simonmicro.irimeasurement.ui.AnalyzeFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.roundToInt

class AnalysisThread(private var view: View, private var fragment: AnalyzeFragment, private var context: Context, private var collectionUUID: UUID, private var useAccelerometer: Boolean, private var useGeocoding: Boolean): Thread() {
    private val log = com.simonmicro.irimeasurement.util.Log(AnalysisThread::class.java.name)
    private var aStatus = AnalyzeFragment.AnalyzeStatus(false)
    private var expectedKillString = "Active analysis thread reference changed - terminating this instance!"

    private var onUpdate = false
    private fun pushViewUpdate(force: Boolean) {
        if(this.fragment.activeAnalysisThread != this)
            // Okay, an other analysis thread was started! We terminate now by throwing an Exception!
            throw Exception(expectedKillString)
        if(this.onUpdate && !force)
            // We are still waiting for one update to finish...
            return
        val that = this
        this.onUpdate = true
        CoroutineScope(Dispatchers.Main).launch {
            that.fragment.updateAnalyzeStatus(view, aStatus)
            that.onUpdate = false
        }
    }

    override fun run() {
        super.run()
        this.aStatus.working = true
        this.aStatus.workingText = context.getString(R.string.analysis_starting)
        this.pushViewUpdate(true)
        try {
            this.fragment.clearMarkers() // Already clear the view
            this.aStatus.workingText = context.getString(R.string.analysis_loading)
            this.pushViewUpdate(true)
            val c = Collection(this.collectionUUID)
            aStatus.resultText = c.toSnackbarString(context)

            // Analyze the data
            this.aStatus.workingText = context.getString(R.string.analysis_parsing)
            this.pushViewUpdate(true)
            val iriSvc = IRICalculationService(collection = c, context = this.fragment.requireContext(), useAccelerometer = useAccelerometer, useGeocoding = useGeocoding)

            // Determine the collected segments
            this.aStatus.workingText = context.getString(R.string.analysis_searching) + "…"
            this.pushViewUpdate(true)
            val progressCallback = {
                description: String, percent: Double ->
                this.aStatus.workingText = context.getString(R.string.analysis_searching) + ": $description…"
                this.aStatus.workingProgress = (percent * 100).toInt()
                this.pushViewUpdate(false)
            }
            val segments = iriSvc.getSectionRecommendations(context, progressCallback)
            this.aStatus.resultText += "\n${context.getString(R.string.analysis_segments_overall)}: ${segments.size}"
            this.pushViewUpdate(true)

            // Add a point for every sections location
            this.aStatus.workingText = context.getString(R.string.analysis_calculating)
            this.pushViewUpdate(true)
            var segmentsSkipped = 0
            var segmentsProcessedIRIAvg = 0.0
            var segmentsLocations = 0
            var lastZoom = 0L
            val iriValues = ArrayList<Double>()
            for (i in segments.indices) {
                if(Date().time - lastZoom > 1000) { // Which would be one second...
                    this.fragment.resetZoom(respectUserLocation = false, animated = true)
                    lastZoom = Date().time
                }
                val segment = segments[i]
                for(location in segment.locations) {
                    if(!location.wasEstimated()) // Every real location will get a section marker!
                        this.fragment.addSegmentMarker(location)
                    else
                        this.fragment.addIntermediateMarker(location)
                    segmentsLocations += 1
                }
                try {
                    val iri: Double = iriSvc.getIRIValue(segment)
                    segmentsProcessedIRIAvg += iri
                    iriValues.add(iri)
                    val iriStr = ((iri * 1000).roundToInt().toDouble() / 1000).toString()
                    this.fragment.addLineMarker(segment.locations, "IRI: $iriStr")
                    this.log.i("IRI of segment ${segment}: $iriStr ($iri)")
                } catch (e: Exception) {
                    segmentsSkipped += 1
                    this.fragment.addLineMarker(segment.locations, e.message)
                    this.log.w("Skipped segment ($segmentsSkipped) ${segment}: ${e.stackTraceToString()}")
                }
                this.aStatus.workingProgress = ((i / segments.size.toDouble()) * 100).toInt()
                this.pushViewUpdate(false)
            }
            this.fragment.resetZoom(respectUserLocation = false, animated = true)
            segmentsProcessedIRIAvg /= iriValues.size.toDouble()
            var segmentsProcessedIRIVar = 0.0
            if(iriValues.size > 1) {
                for (iri in iriValues) {
                    val minus = iri - segmentsProcessedIRIAvg
                    segmentsProcessedIRIVar += minus * minus
                }
                segmentsProcessedIRIVar /= (iriValues.size - 1)
            }
            this.aStatus.resultText += "\n${context.getString(R.string.analysis_segments_skipped)}: $segmentsSkipped"
            this.aStatus.resultText += "\n${context.getString(R.string.analysis_segments_processed)}: ${iriValues.size}"
            this.aStatus.resultText += "\n${context.getString(R.string.analysis_segments_locations)}: $segmentsLocations"
            this.aStatus.resultText += "\n${context.getString(R.string.analysis_segments_avg)}: $segmentsProcessedIRIAvg"
            this.aStatus.resultText += "\n${context.getString(R.string.analysis_segments_var)}: $segmentsProcessedIRIVar"
        } catch(e: Exception) {
            if(e.message == this.expectedKillString) {
                // No! Don't do anything after this point! Just die NOW!
                this.log.i(this.expectedKillString)
                return
            }
            this.log.e(context.getString(R.string.analysis_failed) + e.stackTraceToString())
            aStatus.resultText = context.getString(R.string.analysis_failed_reason) + e.localizedMessage
        }
        this.aStatus.working = false
        this.pushViewUpdate(true)
    }
}
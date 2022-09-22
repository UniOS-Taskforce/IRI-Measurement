package com.simonmicro.irimeasurement

import android.view.View
import com.simonmicro.irimeasurement.services.IRICalculationService
import com.simonmicro.irimeasurement.ui.AnalyzeFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class AnalysisThread(private var view: View, private var fragment: AnalyzeFragment, private var collectionUUID: UUID): Thread() {
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
        var that = this
        this.onUpdate = true
        CoroutineScope(Dispatchers.Main).launch {
            that.fragment.updateAnalyzeStatus(view, aStatus)
            that.onUpdate = false
        }
    }

    override fun run() {
        super.run()
        this.aStatus.working = true
        this.aStatus.workingText = "Starting analysis..."
        this.pushViewUpdate(true)
        try {
            this.fragment.clearMarkers() // Already clear the view
            this.aStatus.workingText = "Loading collection..."
            this.pushViewUpdate(true)
            var c = Collection(this.collectionUUID)
            aStatus.resultText = c.toSnackbarString()

            // Analyze the data
            this.aStatus.workingText = "Parsing data..."
            this.pushViewUpdate(true)
            var iriSvc = IRICalculationService(c, this.fragment.requireContext())

            // Determine the collected segments
            this.aStatus.workingText = "Searching segments..."
            this.pushViewUpdate(true)
            var progressCallback = {
                description: String, percent: Double ->
                this.aStatus.workingText = "Searching segments: $description..."
                this.aStatus.workingProgress = (percent * 100).toInt()
                this.pushViewUpdate(false)
            }
            var segments = iriSvc.getSectionRecommendations(progressCallback)
            this.aStatus.resultText += "\nSegments (overall): ${segments.size}"
            this.pushViewUpdate(true)

            // Add a point for every sections location
            this.aStatus.workingText = "Calculating IRI per segment..."
            this.pushViewUpdate(true)
            var segmentsSkipped: Int = 0
            var segmentsProcessed: Int = 0
            var segmentsProcessedIRIAvg: Double = 0.0
            var segmentsLocations: Int = 0
            for (i in segments.indices) {
                var segment = segments[i]
                for(location in segment.locations) {
                    if(!location.wasEstimated()) // Every real location will get a section marker!
                        this.fragment.addSegmentMarker(location)
                    else
                        this.fragment.addIntermediateMarker(location)
                    segmentsLocations += 1
                }
                try {
                    var iri: Double = iriSvc.getIRIValue(segment)
                    segmentsProcessedIRIAvg += iri
                    segmentsProcessed += 1
                    this.fragment.addLineMarker(segment.locations, "IRI: $iri")
                    this.log.i("IRI of segment ${segment}: $iri")
                } catch (e: Exception) {
                    segmentsSkipped += 1
                    this.fragment.addLineMarker(segment.locations, e.message)
                    this.log.w("Skipped segment ($segmentsSkipped) ${segment}: ${e.stackTraceToString()}")
                }
                this.aStatus.workingProgress = ((i / segments.size.toDouble()) * 100).toInt()
                this.pushViewUpdate(false)
            }
            this.fragment.resetZoom()
            segmentsProcessedIRIAvg /= segmentsProcessed.toDouble()
            this.aStatus.resultText += "\nSegments (skipped): $segmentsSkipped"
            this.aStatus.resultText += "\nSegments (processed): $segmentsProcessed"
            this.aStatus.resultText += "\nSegments locations: $segmentsLocations"
            this.aStatus.resultText += "\nSegments IRI (avg): $segmentsProcessedIRIAvg"
        } catch(e: Exception) {
            if(e.message == this.expectedKillString) {
                // No! Don't do anything after this point! Just die NOW!
                this.log.i(this.expectedKillString)
                return
            }
            this.log.e("Analysis failed: ${e.stackTraceToString()}")
            aStatus.resultText = "Failed to analyze the collection: ${e.message}"
        }
        this.aStatus.working = false
        this.pushViewUpdate(true)
    }
}
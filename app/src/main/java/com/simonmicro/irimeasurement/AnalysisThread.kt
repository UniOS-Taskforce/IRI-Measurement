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

    private fun pushViewUpdate() {
        if(this.fragment.activeAnalysisThread != this)
            // Okay, an other analysis thread was started! We terminate now by throwing an Exception!
            throw Exception(expectedKillString)
        var that = this
        CoroutineScope(Dispatchers.Main).launch { that.fragment.updateAnalyzeStatus(view, aStatus) }
    }

    override fun run() {
        super.run()
        this.aStatus.working = true
        this.aStatus.workingText = "Starting analysis..."
        this.pushViewUpdate()
        try {
            this.aStatus.workingText = "Loading collection..."
            this.pushViewUpdate()
            var c = Collection(this.collectionUUID)
            aStatus.resultText = c.toSnackbarString()

            // Analyze the data
            this.aStatus.workingText = "Parsing data..."
            this.pushViewUpdate()
            var iriSvc = IRICalculationService(c)

            // Determine the collected segments
            this.aStatus.workingText = "Searching segments..."
            this.pushViewUpdate()
            var segments = iriSvc.getSectionRecommendations()
            this.aStatus.resultText += "\nSegments (overall): ${segments.size}"
            this.pushViewUpdate()

            // Add a point for every section start and end
            this.aStatus.workingText = "Calculating IRI per segment..."
            this.pushViewUpdate()
            var segmentsSkipped: Int = 0
            var segmentsProcessed: Int = 0
            var segmentsProcessedIRIAvg: Double = 0.0
            for (i in segments.indices) {
                var segment = segments[i]
                var location = iriSvc.getLocation(segment.start)
                // TODO Move markers into status / results
                this.fragment.addMarker(location.locLat, location.locLon, false)
                location = iriSvc.getLocation(segment.end)
                this.fragment.addMarker(location.locLat, location.locLon, false)
                try {
                    var iri: Double = iriSvc.getIRIValue(segment)
                    segmentsProcessedIRIAvg += iri
                    segmentsProcessed += 1
                    this.log.i("IRI of segment ${segment}: $iri")
                } catch (e: Exception) {
                    segmentsSkipped += 1
                    this.log.w("Skipped segment ($segmentsSkipped) ${segment}: ${e.message}")
                }
                this.aStatus.workingProgress = ((i / segments.size.toDouble()) * 100).toInt()
                this.pushViewUpdate()
            }
            segmentsProcessedIRIAvg /= segmentsProcessed.toDouble()
            this.aStatus.resultText += "\nSegments (skipped): $segmentsSkipped"
            this.aStatus.resultText += "\nSegments (processed): $segmentsProcessed"
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
        this.pushViewUpdate()
    }
}
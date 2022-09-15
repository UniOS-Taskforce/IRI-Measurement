package com.simonmicro.irimeasurement.ui

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import com.simonmicro.irimeasurement.R
import com.simonmicro.irimeasurement.services.CollectorService
import com.simonmicro.irimeasurement.Collection
import com.simonmicro.irimeasurement.CollectionManager
import com.simonmicro.irimeasurement.services.StorageService

class CollectionView(var collection: Collection, private var activity: CollectionManager?, var exporting: Boolean = false) {
    private fun isCollectionInUse(view: View): Boolean {
        val b: Boolean = this.collection.isInUse()
        if(b) Snackbar.make(view, "Collection is in use!", Snackbar.LENGTH_LONG).show()
        return b
    }

    fun updateView(view: View, list: CollectionViewAdapter) {
        val textView1 = view.findViewById<TextView>(R.id.title)
        textView1?.text = collection.getMeta().started.toString()

        val textView2 = view.findViewById<TextView>(R.id.subtitle)
        textView2?.text = "${StorageService.getBytesNormalString(collection.getSizeBytes())}, ${collection.id.toString()}"

        var savBtn: ImageButton = view.findViewById<ImageButton>(R.id.save)
        var delBtn: ImageButton = view.findViewById<ImageButton>(R.id.delete)
        if(activity == null) {
            savBtn.visibility = ImageButton.GONE
            delBtn.visibility = ImageButton.GONE
        } else {
            savBtn.setOnClickListener {
                if (!this.isCollectionInUse(view)) {
                    activity!!.export(this)
                }
            }
            delBtn.setOnClickListener {
                if (!this.isCollectionInUse(view)) {
                    collection.remove()
                    list.remove(this)
                    Snackbar.make(
                        view,
                        "Collection removed: ${collection.id}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }

        val warnImg = view.findViewById<ImageView>(R.id.warnImg)
        if(collection.getMeta().finished != null)
            warnImg.visibility = ImageView.GONE
        else
            warnImg.visibility = ImageView.VISIBLE

        val exportBar = view.findViewById<ProgressBar>(R.id.exportBar)
        exportBar.isIndeterminate = true
        if(this.exporting)
            exportBar.visibility = ProgressBar.VISIBLE
        else
            exportBar.visibility = ProgressBar.GONE
    }
}
package com.simonmicro.irimeasurement.ui

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import com.simonmicro.irimeasurement.R
import com.simonmicro.irimeasurement.services.CollectorService
import com.simonmicro.irimeasurement.Collection

class CollectionView(var collection: Collection) {
    private fun isCollectionInUse(view: View): Boolean {
        val b: Boolean = (CollectorService.instance != null && CollectorService.instance!!.collection!!.id == this.collection.id)
        if(b) Snackbar.make(view, "Collection is in use!", Snackbar.LENGTH_LONG).show()
        return b
    }

    fun updateView(view: View, list: CollectionViewAdapter) {
        val textView1 = view.findViewById<TextView>(R.id.title)
        textView1?.text = collection.getMeta().creation.toString()

        val textView2 = view.findViewById<TextView>(R.id.subtitle)
        textView2?.text = collection.id.toString()

        var savBtn: ImageButton = view.findViewById<ImageButton>(R.id.save)
        var delBtn: ImageButton = view.findViewById<ImageButton>(R.id.delete)
        savBtn.setOnClickListener {
            if(!this.isCollectionInUse(view)) {
                TODO("Not implemented yet")
            }
        }
        delBtn.setOnClickListener {
            if(!this.isCollectionInUse(view)) {
                collection.remove()
                list.remove(this)
                Snackbar.make(view, "Collection removed: ${collection.id}", Snackbar.LENGTH_LONG).show()
            }
        }
    }
}
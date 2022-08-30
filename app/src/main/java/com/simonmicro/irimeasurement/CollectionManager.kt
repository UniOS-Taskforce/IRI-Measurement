package com.simonmicro.irimeasurement

import android.os.Bundle
import android.widget.AdapterView.OnItemClickListener
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.simonmicro.irimeasurement.services.StorageCollection
import com.simonmicro.irimeasurement.services.StorageService
import com.simonmicro.irimeasurement.ui.CollectionView
import com.simonmicro.irimeasurement.ui.CollectionViewAdapter

class CollectionManager : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection_manager)

        // Copy over the current collections into their view
        var l: ArrayList<StorageCollection> = StorageService.listCollections()
        var a: ArrayList<CollectionView> = ArrayList()
        for(c: StorageCollection in l)
            a.add(CollectionView(c))

        val numbersArrayAdapter = CollectionViewAdapter(this, a)
        val numbersListView = findViewById<ListView>(R.id.collectionsList)
        numbersListView.adapter = numbersArrayAdapter
        numbersListView.onItemClickListener = OnItemClickListener { _, view, position, _ ->
            val cv: CollectionView = a[position]
            Snackbar.make(view, cv.collection.toSnackbarString(), Snackbar.LENGTH_LONG).show()
        }
    }
}
package com.simonmicro.irimeasurement

import android.os.Bundle
import android.widget.AdapterView.OnItemClickListener
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.simonmicro.irimeasurement.services.StorageService
import com.simonmicro.irimeasurement.ui.CollectionView
import com.simonmicro.irimeasurement.ui.CollectionViewAdapter
import java.io.OutputStream

class CollectionManager : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection_manager)

        // Copy over the current collections into their view
        var l: ArrayList<Collection> = StorageService.listCollections()
        var a: ArrayList<CollectionView> = ArrayList()
        for(c: Collection in l)
            a.add(CollectionView(c, this))

        val numbersArrayAdapter = CollectionViewAdapter(this, a)
        val numbersListView = findViewById<ListView>(R.id.collectionsList)
        numbersListView.adapter = numbersArrayAdapter
        numbersListView.onItemClickListener = OnItemClickListener { _, view, position, _ ->
            val cv: CollectionView = a[position]
            Snackbar.make(view, cv.collection.toSnackbarString(), Snackbar.LENGTH_LONG).show()
        }
    }

    private var collectionToExportViaContract: Collection? = null
    private val exportCollectionContract = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri == null)
            return@registerForActivityResult
        // Grab the current collection, as it could take a while...
        val collection = this.collectionToExportViaContract!!
        this.collectionToExportViaContract = null
        // Do the export
        Snackbar.make(findViewById(R.id.collectionsList), "Starting export of ${collection.id}...", Snackbar.LENGTH_LONG).show()
        val out: OutputStream = this.contentResolver?.openOutputStream(uri)!!
        collection.export(out)
        out.flush()
        out.close()
        Snackbar.make(findViewById(R.id.collectionsList), "Finished export of ${collection.id}", Snackbar.LENGTH_LONG).show()
    }

    fun export(collection: Collection) {
        this.collectionToExportViaContract = collection
        exportCollectionContract.launch(collection.id.toString() + ".zip")
    }
}
package com.simonmicro.irimeasurement

import android.os.Bundle
import android.widget.AdapterView.OnItemClickListener
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.simonmicro.irimeasurement.services.StorageService
import com.simonmicro.irimeasurement.ui.CollectionView
import com.simonmicro.irimeasurement.ui.CollectionViewAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream

class CollectionManager : AppCompatActivity() {
    private lateinit var collectionsArrayAdapter: CollectionViewAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection_manager)

        // Copy over the current collections into their view
        var l: ArrayList<Collection> = StorageService.listCollections()
        var a: ArrayList<CollectionView> = ArrayList()
        for(c: Collection in l)
            a.add(CollectionView(c, this))

        collectionsArrayAdapter = CollectionViewAdapter(this, a)
        collectionsArrayAdapter.notifyDataSetChanged()
        val numbersListView = findViewById<ListView>(R.id.collectionsList)
        numbersListView.adapter = collectionsArrayAdapter
        numbersListView.onItemClickListener = OnItemClickListener { _, view, position, _ ->
            val cv: CollectionView = a[position]
            cv.collection.reload() // Update the metadata, just in case...
            val snack: Snackbar = Snackbar.make(view, cv.collection.toSnackbarString(), Snackbar.LENGTH_LONG)
            val snackText: TextView = snack.view.findViewById(com.google.android.material.R.id.snackbar_text)
            snackText.maxLines = 12
            snack.show()
        }
    }

    private var collectionToExportViaContract: CollectionView? = null
    private val exportCollectionContract = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri == null)
            return@registerForActivityResult
        // Grab the current collection, as it could take a while...
        val cv: CollectionView = this.collectionToExportViaContract!!
        this.collectionToExportViaContract = null
        // Do the export
        Thread {
            cv.exporting = true
            CoroutineScope(Dispatchers.Main).launch {
                collectionsArrayAdapter.notifyDataSetChanged()
            }
            try {
                Snackbar.make(
                    findViewById(R.id.collectionsList),
                    "Starting export of ${cv.collection.id}...",
                    Snackbar.LENGTH_LONG
                ).show()
                val out: OutputStream = this.contentResolver?.openOutputStream(uri)!!
                cv.collection.export(out)
                out.flush()
                out.close()
                Snackbar.make(
                    findViewById(R.id.collectionsList),
                    "Finished export of ${cv.collection.id}",
                    Snackbar.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Snackbar.make(
                    findViewById(R.id.collectionsList),
                    "Export of ${cv.collection.id} failed: " + e.message,
                    Snackbar.LENGTH_LONG
                ).show()
            }
            cv.exporting = false
            CoroutineScope(Dispatchers.Main).launch {
                collectionsArrayAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    fun export(cv: CollectionView) {
        this.collectionToExportViaContract = cv
        exportCollectionContract.launch(cv.collection.id.toString() + ".zip")
    }
}
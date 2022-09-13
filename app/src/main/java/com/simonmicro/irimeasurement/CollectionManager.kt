package com.simonmicro.irimeasurement

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
import java.io.InputStream
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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.collection_manager_options, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if(item.itemId == R.id.collection_manager_options_import) {
            this.import()
            true
        } else if(item.itemId == R.id.collection_manager_options_close) {
            this.finish()
            true
        } else
            super.onOptionsItemSelected(item)
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

    private val importCollectionContract = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri == null)
            return@registerForActivityResult
        // Do the export
        Thread {
            CoroutineScope(Dispatchers.Main).launch {
                collectionsArrayAdapter.notifyDataSetChanged()
            }
            try {
                Snackbar.make(
                    findViewById(R.id.collectionsList),
                    "Starting import...",
                    Snackbar.LENGTH_LONG
                ).show()
                val inp: InputStream = this.contentResolver?.openInputStream(uri)!!
                val collection: Collection = Collection.import(inp)
                inp.close()
                collectionsArrayAdapter.add(CollectionView(collection, this))
                CoroutineScope(Dispatchers.Main).launch {
                    collectionsArrayAdapter.notifyDataSetChanged()
                }
                Snackbar.make(
                    findViewById(R.id.collectionsList),
                    "Finished import of ${collection.id}",
                    Snackbar.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Snackbar.make(
                    findViewById(R.id.collectionsList),
                    "Import of failed: " + e.message,
                    Snackbar.LENGTH_LONG
                ).show()
            }
            CoroutineScope(Dispatchers.Main).launch {
                collectionsArrayAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    fun export(cv: CollectionView) {
        this.collectionToExportViaContract = cv
        exportCollectionContract.launch(cv.collection.id.toString() + ".zip")
    }

    fun import() {
        val smth: Array<String> = arrayOf("application/zip")
        importCollectionContract.launch(smth)
    }
}
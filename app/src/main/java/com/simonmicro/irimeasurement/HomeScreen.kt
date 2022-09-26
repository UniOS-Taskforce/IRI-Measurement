package com.simonmicro.irimeasurement

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.simonmicro.irimeasurement.databinding.ActivityHomeScreenBinding
import com.simonmicro.irimeasurement.services.CollectorService
import com.simonmicro.irimeasurement.services.LocationService
import com.simonmicro.irimeasurement.services.StorageService
import kotlin.io.path.exists

class HomeScreen : AppCompatActivity() {
    private lateinit var binding: ActivityHomeScreenBinding
    private val log = com.simonmicro.irimeasurement.util.Log(HomeScreen::class.java.name)

    companion object {
        var locService: LocationService? = null // Only to be used by the fragments
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_ACTION_BAR)

        // Init other services
        StorageService.initWithContext(this)

        // Init action bar
        binding = ActivityHomeScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        LocationService.snackbarTarget = this.findViewById(R.id.container)
        locService = LocationService(this, this)
        binding.overlayText.text = BuildConfig.APPLICATION_ID + " v" + BuildConfig.VERSION_NAME

        // Prepare the UI
        supportActionBar!!.show()
        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_home_screen)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.home_screen_actionbar_collect, R.id.home_screen_actionbar_analyze
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        if(!StorageService.getSkipTutorialPath().exists())
            startActivity(Intent(this, TutorialActivity::class.java))
        else
            this.log.d("Found file ${StorageService.getSkipTutorialPath()} - skipping tutorial...")
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.home_screen_options, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if(item.itemId == R.id.home_screen_options_collections) {
            val intent = Intent(this, CollectionManager::class.java)
            startActivity(intent)
            true
        } else
            super.onOptionsItemSelected(item)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locService!!.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear static fields
        LocationService.snackbarTarget = null
        locService = null
    }
}
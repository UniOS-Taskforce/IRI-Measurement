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
import com.simonmicro.irimeasurement.services.LocationService
import com.simonmicro.irimeasurement.services.StorageService
import kotlin.io.path.exists

class HomeScreen : AppCompatActivity() {
    private lateinit var binding: ActivityHomeScreenBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_ACTION_BAR)

        // Init other services
        StorageService.initWithContext(this)

        // Init action bar
        binding = ActivityHomeScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        LocationService.snackbarTarget = this.findViewById(R.id.container)
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

        if(TutorialActivity.shouldDisplayAgain())
            startActivity(Intent(this, TutorialActivity::class.java))
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.home_screen_options, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if(item.itemId == R.id.home_screen_options_collections) {
            startActivity(Intent(this, CollectionManager::class.java))
            true
        } else if(item.itemId == R.id.home_screen_options_show_tutorial) {
            startActivity(Intent(this, TutorialActivity::class.java))
            true
        } else
            super.onOptionsItemSelected(item)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LocationService.onRequestPermissionsResult(this, requestCode, permissions, grantResults) // Forward it as needed
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear static fields
        LocationService.snackbarTarget = null
    }
}
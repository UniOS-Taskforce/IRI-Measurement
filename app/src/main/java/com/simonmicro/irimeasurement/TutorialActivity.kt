package com.simonmicro.irimeasurement

import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.simonmicro.irimeasurement.services.StorageService

class TutorialActivity : AppCompatActivity() {
    companion object {
        private val log = com.simonmicro.irimeasurement.util.Log(TutorialActivity::class.java.name)

        fun shouldDisplayAgain(): Boolean {
            val file = StorageService.getTutorialConfigPath().toFile()
            if(!file.exists())
                return true
            val lastShow = file.bufferedReader().readText()
            return if(lastShow != BuildConfig.VERSION_NAME) {
                log.d("Lastly showed tutorial on $lastShow (now ${BuildConfig.VERSION_NAME})...")
                true
            } else
                false
        }

        fun neverDisplayAgain() {
            val file = StorageService.getTutorialConfigPath().toFile()
            if(!file.exists())
                file.createNewFile()
            log.d("Never showing tutorial again for ${BuildConfig.VERSION_NAME}...")
            file.writer().use {
                it.write(BuildConfig.VERSION_NAME)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.supportActionBar?.hide()
        setContentView(R.layout.activity_tutorial)
        findViewById<TextView>(R.id.tutorialText).movementMethod = LinkMovementMethod.getInstance()
        val repoText = findViewById<TextView>(R.id.repositoryLink)
        repoText.movementMethod = LinkMovementMethod.getInstance()
        repoText.text = Html.fromHtml("<a href=\"" + BuildConfig.repository + "\">" + getString(R.string.tutorial_source_code) + "</a>")
        findViewById<TextView>(R.id.headSubTitle).text = "v" + BuildConfig.VERSION_NAME

        findViewById<Button>(R.id.continueBtn).setOnClickListener {
            this.finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        neverDisplayAgain()
    }
}
package com.simonmicro.irimeasurement

import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.simonmicro.irimeasurement.services.StorageService

class TutorialActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.supportActionBar?.hide()
        setContentView(R.layout.activity_tutorial)
        findViewById<TextView>(R.id.tutorialText).movementMethod = LinkMovementMethod.getInstance()
        val repoText = findViewById<TextView>(R.id.repositoryLink)
        repoText.movementMethod = LinkMovementMethod.getInstance()
        repoText.text = Html.fromHtml("<a href=\"" + BuildConfig.repository + "\">" + getString(R.string.tutorial_source_code) + "</a>")

        findViewById<Button>(R.id.continueBtn).setOnClickListener {
            StorageService.getSkipTutorialPath().toFile().createNewFile()
            this.finish()
        }
    }
}
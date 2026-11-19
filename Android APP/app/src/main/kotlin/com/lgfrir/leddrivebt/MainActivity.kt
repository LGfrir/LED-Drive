package com.lgfrir.leddrivebt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.lgfrir.leddrivebt.ui.LightApp
import com.lgfrir.leddrivebt.ui.LightViewModel

class MainActivity : ComponentActivity() {
    private val model: LightViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LightApp(model)
        }
    }
}

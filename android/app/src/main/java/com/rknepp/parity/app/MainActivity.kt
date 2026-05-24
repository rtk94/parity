package com.rknepp.parity.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rknepp.parity.ParityApplication
import com.rknepp.parity.ServiceLocator

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val locator: ServiceLocator = (application as ParityApplication).serviceLocator
        setContent { ParityApp(locator) }
    }
}

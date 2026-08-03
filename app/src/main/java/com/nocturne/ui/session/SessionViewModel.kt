package com.nocturne.ui.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nocturne.session.SessionController
import com.nocturne.session.SimulatedController

/** Hosts the M1 simulated session controller for the whole UI. */
class SessionViewModel(app: Application) : AndroidViewModel(app) {
    val ctrl: SessionController = SimulatedController(viewModelScope)
}

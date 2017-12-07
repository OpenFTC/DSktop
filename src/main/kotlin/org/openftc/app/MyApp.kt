package org.openftc.app

import com.qualcomm.robotcore.util.ElapsedTime
import org.openftc.network.NetworkConnectionHandler
import org.openftc.view.MainView
import tornadofx.App

class MyApp : App(MainView::class, Styles::class) {
    companion object {
        val runtime = ElapsedTime()
    }

}
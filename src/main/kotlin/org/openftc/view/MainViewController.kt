package org.openftc.view;

import org.openftc.network.NetworkConnectionHandler
import tornadofx.*
import java.net.InetAddress
import java.net.UnknownHostException

class MainViewController : Controller() {
    fun connect(addressString: String) {
        try {
            val address = InetAddress.getByName(addressString)
            NetworkConnectionHandler.getInstance().init(address)
        } catch (e: UnknownHostException) {
            // TODO: display an error
        }


    }

}

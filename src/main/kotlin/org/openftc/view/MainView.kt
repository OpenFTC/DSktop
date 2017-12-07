package org.openftc.view

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.TextField
import org.openftc.app.Styles
import tornadofx.*

class MainView : View("Hello TornadoFX") {

    val controller: MainViewController by inject()
    var addressField: TextField by singleAssign()

    override val root = borderpane {
        label(title) {
            addClass(Styles.heading)
        }
        top {
            hbox(alignment = Pos.CENTER, spacing = 10) {
                padding = Insets(10.0)
                label("RC address")
                addressField = textfield()
                button("Connect") {
                    action {
                        controller.connect(addressField.text)
                    }
                }
            }
        }
    }
}
package org.openftc.network

import com.qualcomm.robotcore.robocol.Command
import com.qualcomm.robotcore.robocol.PeerDiscoveryManager
import com.qualcomm.robotcore.robocol.RobocolDatagramSocket
import com.qualcomm.robotcore.util.ElapsedTime
import org.firstinspires.ftc.robotcore.internal.network.RecvLoopRunnable
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.qualcomm.robotcore.util.RobotLog
import com.qualcomm.robotcore.util.ThreadPool
import java.util.concurrent.TimeUnit


class SetupRunnable(private val rcAddress: InetAddress,
                    private val recvLoopCallback: RecvLoopRunnable.RecvLoopCallback,
                    private val timeSinceLastRrcvPacket: ElapsedTime) : Runnable {

    private val TAG = "SetupRunnable"
    var socket: RobocolDatagramSocket? = null
    private var recvLoopService: ExecutorService? = null
    @Volatile private var recvLoopRunnable: RecvLoopRunnable? = null
    private var peerDiscoveryManager: PeerDiscoveryManager? = null

    private val initLatch = CountDownLatch(1)

    /**
     * throws SocketException
     */
    override fun run() {
        socket?.close()
        socket = RobocolDatagramSocket()
        socket!!.listenUsingDestination(rcAddress)
        socket!!.connect(rcAddress) // The Driver Station is the one to start the connection

        recvLoopService = Executors.newFixedThreadPool(2)
        recvLoopRunnable = RecvLoopRunnable(recvLoopCallback, socket!!, timeSinceLastRrcvPacket)
        val commandProcessor = recvLoopRunnable!!.CommandProcessor()
        NetworkConnectionHandler.getInstance().setRecvLoopRunnable(recvLoopRunnable)
        recvLoopService!!.execute(commandProcessor)
        recvLoopService!!.execute(recvLoopRunnable)

        peerDiscoveryManager?.stop()
        peerDiscoveryManager = PeerDiscoveryManager(socket, rcAddress)

        initLatch.countDown()

        RobotLog.v("Setup complete")
    }

    fun injectReceivedCommand(cmd: Command) {
        if (recvLoopRunnable != null) {
            recvLoopRunnable!!.injectReceivedCommand(cmd)
        } else {
            RobotLog.vv(TAG, "injectReceivedCommand(): recvLoopRunnable==null; command ignored")
        }
    }

    fun shutdown() {
        try {
            // wait for startup to get to a safe point where we can shut it down
            initLatch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        recvLoopService?.let {
            recvLoopService?.shutdownNow()
            ThreadPool.awaitTerminationOrExitApplication(recvLoopService, 5, TimeUnit.SECONDS, "ReceiveLoopService", "internal error")
            recvLoopService = null
            recvLoopRunnable = null
        }

        peerDiscoveryManager?.let {
            peerDiscoveryManager?.stop()
            peerDiscoveryManager = null
        }
    }
}
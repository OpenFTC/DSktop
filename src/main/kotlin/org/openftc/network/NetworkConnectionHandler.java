/*
 * Copyright (c) 2016 Molly Nicholas
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * (subject to the limitations in the disclaimer below) provided that the following conditions are
 * met:
 *
 * Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions
 * and the following disclaimer in the documentation and/or other materials provided with the
 * distribution.
 *
 * Neither the name of Molly Nicholas nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE GRANTED BY THIS LICENSE. THIS
 * SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 * THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.openftc.network;

import com.qualcomm.robotcore.exception.RobotCoreException;
import com.qualcomm.robotcore.robocol.Command;
import com.qualcomm.robotcore.robocol.PeerDiscovery;
import com.qualcomm.robotcore.robocol.RobocolDatagram;
import com.qualcomm.robotcore.robocol.RobocolDatagramSocket;
import com.qualcomm.robotcore.util.ElapsedTime;
import com.qualcomm.robotcore.util.RobotLog;
import org.firstinspires.ftc.robotcore.internal.network.CallbackResult;
import org.firstinspires.ftc.robotcore.internal.network.RecvLoopRunnable;
import org.firstinspires.ftc.robotcore.internal.network.SendOnceRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("WeakerAccess")
public class NetworkConnectionHandler {

    //----------------------------------------------------------------------------------------------
    // Static State
    //----------------------------------------------------------------------------------------------

    public static final String TAG = "NetworkConnectionHandler";
    private static final NetworkConnectionHandler theInstance = new NetworkConnectionHandler();

    public static NetworkConnectionHandler getInstance() {
        return theInstance;
    }

    //----------------------------------------------------------------------------------------------
    // State
    //----------------------------------------------------------------------------------------------
    protected boolean setupNeeded = true;

    protected ElapsedTime lastRecvPacket = new ElapsedTime();
    protected InetAddress rcAddr;
    protected RobocolDatagramSocket socket;
    protected ScheduledExecutorService sendLoopService = Executors.newSingleThreadScheduledExecutor();
    protected ScheduledFuture<?> sendLoopFuture;
    protected SendOnceRunnable sendOnceRunnable;
    protected SetupRunnable setupRunnable;

    protected RecvLoopRunnable recvLoopRunnable;
    protected final RecvLoopCallbackChainer theRecvLoopCallback = new RecvLoopCallbackChainer();
    protected final Object callbackLock = new Object(); // paranoia more than reality, but better safe than sorry. Guards the..Callback vars

    //----------------------------------------------------------------------------------------------
    // Construction
    //----------------------------------------------------------------------------------------------

    private NetworkConnectionHandler() {
        //FIXME: get the IP address from somewhere else. (we may not want to set this up as a singleton, so we can pass it in the constructor)
        try {
            rcAddr = InetAddress.getByName("192.168.49.1");
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    public void init(InetAddress rcAddr) {
        this.rcAddr = rcAddr;
        init();
    }

    public void init() {
//        shutdown();

        if (setupNeeded) {
            setupNeeded = false;
            synchronized (callbackLock) {
                setupRunnable = new SetupRunnable(rcAddr, theRecvLoopCallback, lastRecvPacket);
            }
            (new Thread(setupRunnable)).start();
        }

        // FIXME: Do whatever we need to do to set up the network connection. This may be nothing for this class
    }

    public void setRecvLoopRunnable(RecvLoopRunnable recvLoopRunnable) {
        synchronized (callbackLock) {
            this.recvLoopRunnable = recvLoopRunnable;
            this.recvLoopRunnable.setCallback(theRecvLoopCallback);
        }
    }

    public synchronized void updateConnection
            (
                    @NotNull RobocolDatagram packet,
                    @Nullable SendOnceRunnable.Parameters parameters,
                    SendOnceRunnable.ClientCallback clientCallback
            ) throws RobotCoreException {

        if (packet.getAddress().equals(rcAddr)) {
            if (sendOnceRunnable != null) sendOnceRunnable.onPeerConnected(false);
            if (clientCallback != null) clientCallback.peerConnected(false);
            return;
        }

        if (parameters == null) parameters = new SendOnceRunnable.Parameters();

        // Actually parse the packet in order to verify Robocol version compatibility
        PeerDiscovery peerDiscovery = PeerDiscovery.forReceive();
        peerDiscovery.fromByteArray(packet.getData());

        // update rcAddr with latest address
        rcAddr = packet.getAddress();
        RobotLog.vv(PeerDiscovery.TAG, "new remote peer discovered: " + rcAddr.getHostAddress());

        if (socket == null && setupRunnable != null) {
            socket = setupRunnable.getSocket();
        }

        if (socket != null) {
            try {
                socket.connect(rcAddr);
            } catch (SocketException e) {
                throw RobotCoreException.createChained(e, "unable to connect to %s", rcAddr.toString());
            }

            // start send loop, if needed
            if (sendLoopFuture == null || sendLoopFuture.isDone()) {
                RobotLog.vv(TAG, "starting sending loop");
                sendOnceRunnable = new SendOnceRunnable(clientCallback, socket, lastRecvPacket, parameters);
                sendLoopFuture = sendLoopService.scheduleAtFixedRate(sendOnceRunnable, 0, 40, TimeUnit.MILLISECONDS);
            }

            if (sendOnceRunnable != null) sendOnceRunnable.onPeerConnected(true);
            if (clientCallback != null) clientCallback.peerConnected(true);
        }
    }

    // synchronized avoids race with shutdown()
    public synchronized boolean removeCommand(Command cmd) {
        return (sendOnceRunnable != null) && sendOnceRunnable.removeCommand(cmd);
    }

    // synchronized avoids race with shutdown()
    public synchronized void sendCommand(Command cmd) {
        if (sendOnceRunnable != null) sendOnceRunnable.sendCommand(cmd);
    }

    // synchronized avoids race with shutdown()
    public synchronized void sendReply(Command commandRequest, Command commandResponse) {
        if (wasTransmittedRemotely(commandRequest)) {
            sendCommand(commandResponse);
        } else {
            injectReceivedCommand(commandResponse);
        }
    }

    protected boolean wasTransmittedRemotely(Command command) {
        return !command.isInjected();
    }

    /**
     * Inject the indicated command into the reception infrastructure as if it had been transmitted remotely
     */
    public synchronized void injectReceivedCommand(Command cmd) {
        if (setupRunnable != null) {
            cmd.setIsInjected(true);
            setupRunnable.injectReceivedCommand(cmd);
        } else {
            RobotLog.vv(TAG, "injectReceivedCommand(): setupRunnable==null; command ignored");
        }
    }

    public CallbackResult processAcknowledgments(Command command) throws RobotCoreException {
        if (command.isAcknowledged()) {
            if (SendOnceRunnable.DEBUG)
                RobotLog.vv(SendOnceRunnable.TAG, "received ack: %s(%d)", command.getName(), command.getSequenceNumber());
            removeCommand(command);
            return CallbackResult.HANDLED;
        }
        // Note: this is an expensive approach to exactly-once datagram transmission. We should avoid (re)sending the message body in the ack
        command.acknowledge();
        sendCommand(command);
        return CallbackResult.NOT_HANDLED;
    }

    public synchronized void sendDatagram(RobocolDatagram datagram) {
        if (socket != null && socket.getInetAddress() != null) socket.send(datagram);
    }

    public synchronized void clientDisconnect() {
        if (sendOnceRunnable != null) sendOnceRunnable.clearCommands();
        rcAddr = null;
    }

    public synchronized void shutdown() {
        // shutdown logic tries to take state back to what it was before setup, etc, happened

        if (setupRunnable != null) {
            setupRunnable.shutdown();
            setupRunnable = null;
        }

        if (sendLoopFuture != null) {
            sendLoopFuture.cancel(true);
            sendOnceRunnable = null;
            sendLoopFuture = null;
        }

        // close the socket as well
        if (socket != null) {
            socket.close();
            socket = null;
        }

        // reset the client
        rcAddr = null;

        // reset need for handleConnectionInfoAvailable
        setupNeeded = true;
    }

    //----------------------------------------------------------------------------------------------
    // Callback chainers
    // Here we find the *actual* classes we register for callback notifications of various
    // forms. Internally, they maintain chains of external, registered callbacks, to whom they
    // delegate.
    //----------------------------------------------------------------------------------------------

    public void pushReceiveLoopCallback(@Nullable RecvLoopRunnable.RecvLoopCallback callback) {
        synchronized (callbackLock) {
            this.theRecvLoopCallback.push(callback);
        }
    }

    public void removeReceiveLoopCallback(@Nullable RecvLoopRunnable.RecvLoopCallback callback) {
        synchronized (callbackLock) {
            this.theRecvLoopCallback.remove(callback);
        }
    }

    protected class RecvLoopCallbackChainer implements RecvLoopRunnable.RecvLoopCallback {

        protected final CopyOnWriteArrayList<RecvLoopRunnable.RecvLoopCallback> callbacks = new CopyOnWriteArrayList<RecvLoopRunnable.RecvLoopCallback>();

        void push(@Nullable RecvLoopRunnable.RecvLoopCallback callback) {
            synchronized (callbacks) {  // for uniqueness testing
                remove(callback);
                if (callback != null && !callbacks.contains(callback)) {
                    callbacks.add(0, callback);
                }
            }
        }

        void remove(@Nullable RecvLoopRunnable.RecvLoopCallback callback) {
            synchronized (callbacks) {
                if (callback != null) callbacks.remove(callback);
            }
        }

        @Override
        public CallbackResult packetReceived(RobocolDatagram packet) throws RobotCoreException {
            for (RecvLoopRunnable.RecvLoopCallback callback : callbacks) {
                CallbackResult result = callback.packetReceived(packet);
                if (result.stopDispatch()) {
                    return CallbackResult.HANDLED;
                }
            }
            return CallbackResult.NOT_HANDLED;
        }

        @Override
        public CallbackResult peerDiscoveryEvent(RobocolDatagram packet) throws RobotCoreException {
            for (RecvLoopRunnable.RecvLoopCallback callback : callbacks) {
                CallbackResult result = callback.peerDiscoveryEvent(packet);
                if (result.stopDispatch()) {
                    return CallbackResult.HANDLED;
                }
            }
            return CallbackResult.NOT_HANDLED;
        }

        @Override
        public CallbackResult heartbeatEvent(RobocolDatagram packet, long tReceived) throws RobotCoreException {
            for (RecvLoopRunnable.RecvLoopCallback callback : callbacks) {
                CallbackResult result = callback.heartbeatEvent(packet, tReceived);
                if (result.stopDispatch()) {
                    return CallbackResult.HANDLED;
                }
            }
            return CallbackResult.NOT_HANDLED;
        }

        @Override
        public CallbackResult commandEvent(Command command) throws RobotCoreException {
            boolean handled = false;
            for (RecvLoopRunnable.RecvLoopCallback callback : callbacks) {
                CallbackResult result = callback.commandEvent(command);
                handled = handled || result.isHandled();
                if (result.stopDispatch()) {
                    return CallbackResult.HANDLED;
                }
            }

            if (!handled) {
                // Make an informative trace message as to who was around that all refused to process the command
                StringBuilder callbackNames = new StringBuilder();
                for (RecvLoopRunnable.RecvLoopCallback callback : callbacks) {
                    if (callbackNames.length() > 0) callbackNames.append(",");
                    callbackNames.append(callback.getClass().getSimpleName());
                }
                RobotLog.vv(RobocolDatagram.TAG, "unable to process command %s callbacks=%s", command.getName(), callbackNames.toString());
            }
            return handled ? CallbackResult.HANDLED : CallbackResult.NOT_HANDLED;
        }

        @Override
        public CallbackResult telemetryEvent(RobocolDatagram packet) throws RobotCoreException {
            for (RecvLoopRunnable.RecvLoopCallback callback : callbacks) {
                CallbackResult result = callback.telemetryEvent(packet);
                if (result.stopDispatch()) {
                    return CallbackResult.HANDLED;
                }
            }
            return CallbackResult.NOT_HANDLED;
        }

        @Override
        public CallbackResult gamepadEvent(RobocolDatagram packet) throws RobotCoreException {
            for (RecvLoopRunnable.RecvLoopCallback callback : callbacks) {
                CallbackResult result = callback.gamepadEvent(packet);
                if (result.stopDispatch()) {
                    return CallbackResult.HANDLED;
                }
            }
            return CallbackResult.NOT_HANDLED;
        }

        @Override
        public CallbackResult emptyEvent(RobocolDatagram packet) throws RobotCoreException {
            for (RecvLoopRunnable.RecvLoopCallback callback : callbacks) {
                CallbackResult result = callback.emptyEvent(packet);
                if (result.stopDispatch()) {
                    return CallbackResult.HANDLED;
                }
            }
            return CallbackResult.NOT_HANDLED;
        }

        @Override
        public CallbackResult reportGlobalError(String error, boolean recoverable) {
            for (RecvLoopRunnable.RecvLoopCallback callback : callbacks) {
                CallbackResult result = callback.reportGlobalError(error, recoverable);
                if (result.stopDispatch()) {
                    return CallbackResult.HANDLED;
                }
            }
            return CallbackResult.NOT_HANDLED;
        }
    }
}

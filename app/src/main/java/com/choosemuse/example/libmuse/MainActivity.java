/**
 * Example of using libmuse library on android.
 * Interaxon, Inc. 2016
 */

package com.choosemuse.example.libmuse;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

import java.util.concurrent.atomic.AtomicReference;

import com.choosemuse.libmuse.Accelerometer;
import com.choosemuse.libmuse.AnnotationData;
import com.choosemuse.libmuse.ConnectionState;
import com.choosemuse.libmuse.Eeg;
import com.choosemuse.libmuse.LibmuseVersion;
import com.choosemuse.libmuse.MessageType;
import com.choosemuse.libmuse.Muse;
import com.choosemuse.libmuse.MuseArtifactPacket;
import com.choosemuse.libmuse.MuseConfiguration;
import com.choosemuse.libmuse.MuseConnectionListener;
import com.choosemuse.libmuse.MuseConnectionPacket;
import com.choosemuse.libmuse.MuseDataListener;
import com.choosemuse.libmuse.MuseDataPacket;
import com.choosemuse.libmuse.MuseDataPacketType;
import com.choosemuse.libmuse.MuseFileFactory;
import com.choosemuse.libmuse.MuseFileReader;
import com.choosemuse.libmuse.MuseFileWriter;
import com.choosemuse.libmuse.MuseListener;
import com.choosemuse.libmuse.MuseManagerAndroid;
import com.choosemuse.libmuse.MuseVersion;
import com.choosemuse.libmuse.Result;
import com.choosemuse.libmuse.ResultLevel;
import com.unity3d.player.UnityPlayer;
import com.unity3d.player.UnityPlayerActivity;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.os.Handler;
import android.util.Log;
import android.widget.ArrayAdapter;

//import android.support.v4.app.ActivityCompat;
//import android.support.v4.content.ContextCompat;

/**
 * This example will illustrate how to connect to a Muse headband,
 * register for and receive EEG data and disconnect from the headband.
 * Saving EEG data to a .muse file is also covered.
 *
 * For instructions on how to pair your headband with your Android device
 * please see:
 * http://developer.choosemuse.com/hardware-firmware/bluetooth-connectivity/developer-sdk-bluetooth-connectivity-2
 *
 * Usage instructions:
 * 1. Pair your headband if necessary.
 * 2. Run this project.
 * 3. Turn on the Muse headband.
 * 4. Press "Refresh". It should display all paired Muses in the Spinner drop down at the
 *    top of the screen.  It may take a few seconds for the headband to be detected.
 * 5. Select the headband you want to connect to and press "Connect".
 * 6. You should see EEG and accelerometer data as well as connection status,
 *    version information and relative alpha values appear on the screen.
 * 7. You can pause/resume data transmission with the button at the bottom of the screen.
 * 8. To disconnect from the headband, press "Disconnect"
 */
public class MainActivity {

  private Context context;
    /**
     * Tag used for logging purposes.
     */
    private final String TAG = "MUSE";

    /**
     * The MuseManager is how you detect Muse headbands and receive notifications
     * when the list of available headbands changes.
     */
    private MuseManagerAndroid manager;

    /**
     * A Muse refers to a Muse headband.  Use this to connect/disconnect from the
     * headband, register listeners to receive EEG data and get headband
     * configuration and version information.
     */
    private Muse muse;

    /**
     * The ConnectionListener will be notified whenever there is a change in
     * the connection state of a headband, for example when the headband connects
     * or disconnects.
     *
     * Note that ConnectionListener is an inner class at the bottom of this file
     * that extends MuseConnectionListener.
     */
    private ConnectionListener connectionListener;

    /**
     * The DataListener is how you will receive EEG (and other) data from the
     * headband.
     *
     * Note that DataListener is an inner class at the bottom of this file
     * that extends MuseDataListener.
     */
    private DataListener dataListener;

    /**
     * Data comes in from the headband at a very fast rate; 220Hz, 256Hz or 500Hz,
     * depending on the type of headband and the preset configuration.  We buffer the
     * data that is read until we can update the UI.
     *
     * The stale flags indicate whether or not new data has been received and the buffers
     * hold the values of the last data packet received.  We are displaying the EEG, ALPHA_RELATIVE
     * and ACCELEROMETER values in this example.
     *
     * Note: the array lengths of the buffers are taken from the comments in
     * MuseDataPacketType, which specify 3 values for accelerometer and 6
     * values for EEG and EEG-derived packets.
     */
    private final double[] eegBuffer = new double[6];
    private boolean eegStale;
    private final double[] alphaBuffer = new double[6];
    private boolean alphaStale;
    private final double[] accelBuffer = new double[3];
    private boolean accelStale;

  private boolean isConnected = false;

    /**
     * We will be updating the UI using a handler instead of in packet handlers because
     * packets come in at a very high frequency and it only makes sense to update the UI
     * at about 60fps. The update functions do some string allocation, so this reduces our memory
     * footprint and makes GC pauses less frequent/noticeable.
     */
//    private final Handler handler = new Handler();

    /**
     * It is possible to pause the data transmission from the headband.  This boolean tracks whether
     * or not the data transmission is enabled as we allow the user to pause transmission in the UI.
     */
    private boolean dataTransmission = true;

    /**
     * To save data to a file, you should use a MuseFileWriter.  The MuseFileWriter knows how to
     * serialize the data packets received from the headband into a compact binary format.
     * To read the file back, you would use a MuseFileReader.
     */
    private final AtomicReference<MuseFileWriter> fileWriter = new AtomicReference<>();

    /**
     * We don't want file operations to slow down the UI, so we will defer those file operations
     * to a handler on a separate thread.
     */
    private final AtomicReference<Handler> fileHandler = new AtomicReference<>();


    //--------------------------------------
    // Lifecycle / Connection code

  private static MainActivity instance;

  public MainActivity(Context context) {
    this.instance = this;
    this.context = context;

    // We need to set the context on MuseManagerAndroid before we can do anything.
    // This must come before other LibMuse API calls as it also loads the library.
    manager = MuseManagerAndroid.getInstance();
    manager.setContext(context);

    Log.d(TAG, "onCreate");
    Log.d(TAG, "LibMUSE version=" + LibmuseVersion.instance().getString());

    WeakReference<MainActivity> weakActivity =
        new WeakReference<MainActivity>(this);
    // Register a listener to receive connection state changes.
    connectionListener = new ConnectionListener(weakActivity);
    // Register a listener to receive data from a Muse.
    dataListener = new DataListener(weakActivity);
    // Register a listener to receive notifications of what Muse headbands
    // we can connect to.
    manager.setMuseListener(new MuseL(weakActivity));

    // Muse 2016 (MU-02) headbands use Bluetooth Low Energy technology to
    // simplify the connection process.  This requires access to the COARSE_LOCATION
    // or FINE_LOCATION permissions.  Make sure we have these permissions before
    // proceeding.
//        ensurePermissions();
    manager.stopListening();
    manager.startListening();

    // Start up a thread for asynchronous file operations.
    // This is only needed if you want to do File I/O.
//        fileThread.start();
    refreshThread.start();

    // Start our asynchronous updates of the UI.
//    handler.post(tickUi);
  }

    public static MainActivity getInstance(Context context) {
//        super.onCreate(savedInstanceState);
      if(instance == null) {
        instance = new MainActivity(context);
      }
      return instance;
    }

    protected void onPause() {
//        super.onPause();
        // It is important to call stopListening when the Activity is paused
        // to avoid a resource leak from the LibMuse library.
        manager.stopListening();
    }

  private void refreshMuse() {
    manager.stopListening();
    manager.startListening();
  }

  private void connectToMuse(int museSpinnerPosition) {
    // The user has pressed the "Connect" button to connect to
    // the headband in the spinner.

    // Listening is an expensive operation, so now that we know
    // which headband the user wants to connect to we can stop
    // listening for other headbands.
    manager.stopListening();

    List<Muse> availableMuses = manager.getMuses();

    Log.d("MUSE", "availableMuses: "+availableMuses.size());
    if (availableMuses.size() >= 1) {
      // Cache the Muse that the user has selected.
      muse = availableMuses.get(museSpinnerPosition);
      // Unregister all prior listeners and register our data listener to
      // receive the MuseDataPacketTypes we are interested in.  If you do
      // not register a listener for a particular data type, you will not
      // receive data packets of that type.
      muse.unregisterAllListeners();
      muse.registerConnectionListener(connectionListener);
      muse.registerDataListener(dataListener, MuseDataPacketType.EEG);
      muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE);
      muse.registerDataListener(dataListener, MuseDataPacketType.ACCELEROMETER);
      muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY);
      muse.registerDataListener(dataListener, MuseDataPacketType.DRL_REF);
      muse.registerDataListener(dataListener, MuseDataPacketType.QUANTIZATION);

      // Initiate a connection to the headband and stream the data asynchronously.
      muse.runAsynchronously();
    }
  }

    //--------------------------------------
    // Listeners

    /**
     * You will receive a callback to this method each time a headband is discovered.
     * In this example, we update the spinner with the MAC address of the headband.
     */
    public void museListChanged() {
        final List<Muse> list = manager.getMuses();
      int i =0;
        for (Muse m : list) {
          String name = m.getName() + " - " + m.getMacAddress();
          Log.d("MUSE", name);
          if (name.equals("Muse-5390 - 00:06:66:6F:53:90")) {
            connectToMuse(i);
          }
          i++;
        }
    }

    /**
     * You will receive a callback to this method each time there is a change to the
     * connection state of one of the headbands.
     * @param p     A packet containing the current and prior connection states
     * @param muse  The headband whose state changed.
     */
    public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {

      Log.d(TAG, "receiveMuseConnectionPacket");
        final ConnectionState current = p.getCurrentConnectionState();

        // Format a message to show the change of connection state in the UI.
        final String status = p.getPreviousConnectionState() + " -> " + current;
      if (current.equals("CONNECTED")) {
        isConnected = true;
        Log.d(TAG, "is connected");
      }
      else {
        isConnected = false;
        Log.d(TAG, "is NOT connected");
      }
      sendStatusToUnity(status);
        Log.i(TAG, status);

        if (current == ConnectionState.DISCONNECTED) {
            Log.i(TAG, "MUSE disconnected:" + muse.getName());
            // Save the data file once streaming has stopped.
//            saveFile();
            // We have disconnected from the headband, so set our cached copy to null.
            this.muse = null;
        }
    }

    /**
     * You will receive a callback to this method each time the headband sends a MuseDataPacket
     * that you have registered.  You can use different listeners for different packet types or
     * a single listener for all packet types as we have done here.
     * @param p     The data packet containing the data from the headband (eg. EEG data)
     * @param muse  The headband that sent the information.
     */
    public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
        writeDataPacketToFile(p);
      Log.d(TAG, "receiveMuseDataPacket");
          // valuesSize returns the number of data values contained in the packet.
        final long n = p.valuesSize();
        switch (p.packetType()) {
            case EEG:
                assert(eegBuffer.length >= n);
                getEegChannelValues(eegBuffer,p);
                eegStale = true;
                break;
            case ACCELEROMETER:
                assert(accelBuffer.length >= n);
                getAccelValues(p);
                accelStale = true;
                break;
            case ALPHA_RELATIVE:
                assert(alphaBuffer.length >= n);
              getAlphaChannelValues(alphaBuffer, p);
                alphaStale = true;
                break;
            case BATTERY:
            case DRL_REF:
            case QUANTIZATION:
            default:
                break;
        }
    }

    /**
     * You will receive a callback to this method each time an artifact packet is generated if you
     * have registered for the ARTIFACTS data type.  MuseArtifactPackets are generated when
     * eye blinks are detected, the jaw is clenched and when the headband is put on or removed.
     * @param p     The artifact packet with the data from the headband.
     * @param muse  The headband that sent the information.
     */
    public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
    }

    /**
     * Helper methods to get different packet values.  These methods simply store the
     * data in the buffers for later display in the UI.
     *
     * getEegChannelValue can be used for any EEG or EEG derived data packet type
     * such as EEG, ALPHA_ABSOLUTE, ALPHA_RELATIVE or HSI_PRECISION.  See the documentation
     * of MuseDataPacketType for all of the available values.
     * Specific packet types like ACCELEROMETER, GYRO, BATTERY and DRL_REF have their own
     * getValue methods.
     */
    private void getEegChannelValues(double[] buffer, MuseDataPacket p) {
        buffer[0] = p.getEegChannelValue(Eeg.EEG1);
        buffer[1] = p.getEegChannelValue(Eeg.EEG2);
        buffer[2] = p.getEegChannelValue(Eeg.EEG3);
        buffer[3] = p.getEegChannelValue(Eeg.EEG4);
        buffer[4] = p.getEegChannelValue(Eeg.AUX_LEFT);
        buffer[5] = p.getEegChannelValue(Eeg.AUX_RIGHT);
      String eegString = "";
      for (double value: buffer) {
        eegString += value + " ";
      }
      sendEegToUnity(eegString);
    }

  private void getAlphaChannelValues(double[] buffer, MuseDataPacket p) {
    buffer[0] = p.getEegChannelValue(Eeg.EEG1);
    buffer[1] = p.getEegChannelValue(Eeg.EEG2);
    buffer[2] = p.getEegChannelValue(Eeg.EEG3);
    buffer[3] = p.getEegChannelValue(Eeg.EEG4);
    buffer[4] = p.getEegChannelValue(Eeg.AUX_LEFT);
    buffer[5] = p.getEegChannelValue(Eeg.AUX_RIGHT);
    String alphaString = "";
    for (double value: buffer) {
      alphaString += value + " ";
    }
    float total = 0;
    for (int i=0; i<4; i++) {
      double value= buffer[i];
      if (value>0 && value <1) {
        total += value;
      }
    }
    sendAlphaToUnity(Float.toString(total));
  }

    private void getAccelValues(MuseDataPacket p) {
        accelBuffer[0] = p.getAccelerometerValue(Accelerometer.FORWARD_BACKWARD);
        accelBuffer[1] = p.getAccelerometerValue(Accelerometer.UP_DOWN);
        accelBuffer[2] = p.getAccelerometerValue(Accelerometer.LEFT_RIGHT);
      String accelString = "";
      for (double value: accelBuffer) {
        accelString += value + " ";
      }
      sendAccelToUnity(accelString);
    }

  private void sendStatusToUnity(String status) {
    Log.d("MUSE", "sendStatusToUnity");
    UnityPlayer.UnitySendMessage("Listener", "receiveStatus", status);
  }

  private void sendAccelToUnity(String accel) {
    Log.d("MUSE", "sendAccelToUnity");
    UnityPlayer.UnitySendMessage("Listener", "receiveAccel", accel);
  }

  private void sendEegToUnity(String eeg) {
    Log.d("MUSE", "sendEegToUnity");
    UnityPlayer.UnitySendMessage("Listener", "receiveEeg", eeg);
  }

  private void sendAlphaToUnity(String alpha) {
    Log.d("MUSE", "sendAlphaToUnity");
    UnityPlayer.UnitySendMessage("Listener", "receiveAlpha", alpha);
  }

    /**
     * The runnable that is used to update the UI at 60Hz.
     *
     * We update the UI from this Runnable instead of in packet handlers
     * because packets come in at high frequency -- 220Hz or more for raw EEG
     * -- and it only makes sense to update the UI at about 60fps. The update
     * functions do some string allocation, so this reduces our memory
     * footprint and makes GC pauses less frequent/noticeable.
     */
//    private final Runnable tickUi = new Runnable() {
//        @Override
//        public void run() {
//            if (eegStale) {
//                updateEeg();
//            }
//            if (accelStale) {
//                updateAccel();
//            }
//            if (alphaStale) {
//                updateAlpha();
//            }
//            handler.postDelayed(tickUi, 1000 / 60);
//        }
//    };

    /**
     * The following methods update the TextViews in the UI with the data
     * from the buffers.
     */
    private void updateAccel() {
      Log.d("MUSE", "accelBuffer[0]: "+String.format("%6.2f", accelBuffer[0]));
      Log.d("MUSE", "accelBuffer[1]: "+String.format("%6.2f", accelBuffer[1]));
      Log.d("MUSE", "accelBuffer[2]: "+String.format("%6.2f", accelBuffer[2]));
    }

    private void updateEeg() {
      Log.d("MUSE", "eegBuffer[0]: "+String.format("%6.2f", eegBuffer[0])); //eeg_tp9
      Log.d("MUSE", "eegBuffer[1]: "+String.format("%6.2f", eegBuffer[1])); //eeg_af7
      Log.d("MUSE", "eegBuffer[2]: "+String.format("%6.2f", eegBuffer[2])); //eeg_af8
      Log.d("MUSE", "eegBuffer[3]: "+String.format("%6.2f", eegBuffer[3])); //eeg_tp10
    }

    private void updateAlpha() {
      Log.d("MUSE", "alphaBuffer[0]: "+String.format("%6.2f", alphaBuffer[0])); //eeg_tp9
      Log.d("MUSE", "alphaBuffer[1]: "+String.format("%6.2f", alphaBuffer[1])); //eeg_af7
      Log.d("MUSE", "alphaBuffer[2]: "+String.format("%6.2f", alphaBuffer[2])); //eeg_af8
      Log.d("MUSE", "alphaBuffer[3]: "+String.format("%6.2f", alphaBuffer[3])); //eeg_tp10
    }


    //--------------------------------------
    // File I/O

    /**
     * We don't want to block the UI thread while we write to a file, so the file
     * writing is moved to a separate thread.
     */
    private final Thread fileThread = new Thread() {
        @Override
        public void run() {
            Looper.prepare();
            fileHandler.set(new Handler());
            final File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            final File file = new File(dir, "new_muse_file.muse" );
            // MuseFileWriter will append to an existing file.
            // In this case, we want to start fresh so the file
            // if it exists.
            if (file.exists()) {
                file.delete();
            }
            Log.i(TAG, "Writing data to: " + file.getAbsolutePath());
            fileWriter.set(MuseFileFactory.getMuseFileWriter(file));
            Looper.loop();
        }
    };

  private final Thread refreshThread = new Thread() {
    @Override
    public void run() {
      while (!isConnected) {
        refreshMuse();
        try {
          Thread.sleep(5000);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      Looper.loop();
    }
  };
    /**
     * Writes the provided MuseDataPacket to the file.  MuseFileWriter knows
     * how to write all packet types generated from LibMuse.
     * @param p     The data packet to write.
     */
    private void writeDataPacketToFile(final MuseDataPacket p) {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    fileWriter.get().addDataPacket(0, p);
                }
            });
        }
    }

    /**
     * Flushes all the data to the file and closes the file writer.
     */
    private void saveFile() {
        Handler h = fileHandler.get();
        if (h != null) {
            h.post(new Runnable() {
                @Override public void run() {
                    MuseFileWriter w = fileWriter.get();
                    // Annotation strings can be added to the file to
                    // give context as to what is happening at that point in
                    // time.  An annotation can be an arbitrary string or
                    // may include additional AnnotationData.
                    w.addAnnotationString(0, "Disconnected");
                    w.flush();
                    w.close();
                }
            });
        }
    }

    //--------------------------------------
    // Listener translators
    //
    // Each of these classes extend from the appropriate listener and contain a weak reference
    // to the activity.  Each class simply forwards the messages it receives back to the Activity.
    class MuseL extends MuseListener {
        final WeakReference<MainActivity> activityRef;

        MuseL(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void museListChanged() {
            activityRef.get().museListChanged();
        }
    }

    class ConnectionListener extends MuseConnectionListener {
        final WeakReference<MainActivity> activityRef;

        ConnectionListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseConnectionPacket(final MuseConnectionPacket p, final Muse muse) {
            activityRef.get().receiveMuseConnectionPacket(p, muse);
        }
    }

    class DataListener extends MuseDataListener {
        final WeakReference<MainActivity> activityRef;

        DataListener(final WeakReference<MainActivity> activityRef) {
            this.activityRef = activityRef;
        }

        @Override
        public void receiveMuseDataPacket(final MuseDataPacket p, final Muse muse) {
            activityRef.get().receiveMuseDataPacket(p, muse);
        }

        @Override
        public void receiveMuseArtifactPacket(final MuseArtifactPacket p, final Muse muse) {
            activityRef.get().receiveMuseArtifactPacket(p, muse);
        }
    }
}

package com.example.sleepapneatest;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.jjoe64.graphview.DefaultLabelFormatter;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;

import static android.Manifest.permission.RECORD_AUDIO;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private SerialService service;
    private int timeVar = 15;
    private Button vibrateBt;
    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;
    private Spinner spinner;
    private int numEvents = 0;
    private int vibSpeed = 0;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private boolean apneaEvent = false;
    private String newline = TextUtil.newline_crlf;

    private boolean alarmEnable = false;
    private boolean soundApnea = false;
    private int receiveCounter = 0;
    private int acc_y = 0;
    private int acc_z = 0;
    private int jt = 0;
    private int breathInt = 0;
    private double roll = 0;
    private int breathRate = 0;

    private static final String TAG = "VoiceRecord";
    private Button startbtn, stopbtn, playbtn, stopplay;
    private TextView dispAmpl, state;
    private AudioRecord mRecorder;
    private MediaPlayer mPlayer;
    private int mSampleRate;
    private short mAudioFormat;
    private short mChannelConfig;
    private int mBufferSize = AudioRecord.ERROR_BAD_VALUE;
    private short[] mBuffer;
    private int mLocks = 0;
    private boolean isRecording = false;
    private Thread recordingThread = null;
    private Thread recordingThread1 = null;
    private int snoringState = 0;

    private static final int RECORDER_BPP = 16;
    private static final String AUDIO_RECORDER_FILE_EXT_WAV = ".wav";
    private static final String AUDIO_RECORDER_FOLDER = "AudioRecorder";
    private static final String AUDIO_RECORDER_TEMP_FILE = "record_temp.raw";
    private static final int RECORDER_SAMPLERATE = 7800;
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_STEREO;
    private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    short[] audioData;
    int[] bufferData;
    int bytesRecorded;

    private static final int RECORDER_CHANNELS_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_CHANNELS_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;

    Calendar c = Calendar.getInstance();
    SimpleDateFormat df = new SimpleDateFormat("ddMMMyyyy_HHmmss");
    SimpleDateFormat sdf = new SimpleDateFormat("mm:ss");
    String formattedDate = df.format(c.getTime());

    private static final float MAX_REPORTABLE_AMP = 32767f;
    private static final float MAX_REPORTABLE_DB = 90.3087f;

    float Amplitude = 0;

    GraphView graphView;
    LineGraphSeries<DataPoint> series;

    private static final String LOG_TAG = "AudioRecording";
    private static String mFileName = null;
    public static final int REQUEST_AUDIO_PERMISSION_CODE = 1;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");

    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.terminal_fragment, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        startbtn = view.findViewById(R.id.btnRecord);
        stopbtn = view.findViewById(R.id.btnStop);
        playbtn = view.findViewById(R.id.btnPlay);
        stopplay = view.findViewById(R.id.btnStopPlay);
        dispAmpl = view.findViewById(R.id.amplitude);
        state = view.findViewById(R.id.situation);
        graphView = view.findViewById(R.id.graphid);

        stopbtn.setEnabled(false);
        playbtn.setEnabled(true);
        stopplay.setEnabled(false);

        spinner = view.findViewById(R.id.spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(getActivity(), R.array.numbers, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        vibSpeed = 0;
                        break;
                    case 1:
                        vibSpeed = 1;
                        break;
                    case 2:
                        vibSpeed = 2;
                        break;
                    case 3:
                        vibSpeed = 3;
                        break;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                vibSpeed = 0;
            }
        });

        vibrateBt = view.findViewById(R.id.vibrate);
        vibrateBt.setOnClickListener(v -> {
            send(Integer.toString(vibSpeed));
        });

        //hexWatcher = new TextUtil.HexWatcher(sendText);
        //hexWatcher.enable(hexEnabled);
        //sendText.addTextChangedListener(hexWatcher);
        //sendText.setHint(hexEnabled ? "HEX mode" : "");

        Button alarmButton = view.findViewById(R.id.alarmButton);
        alarmButton.setOnClickListener(v -> {
            if (alarmEnable) {
                alarmEnable = false;
                send(Integer.toString(0));
            }
            else
                alarmEnable = true;
        });

        startbtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(CheckPermissions()) {

                    final File logFile = new File("sdcard/log_"+formattedDate+".txt");
                    File mFileName = new File(Environment.getExternalStorageDirectory().getAbsolutePath(), "AudioRecording.wav");

                    if (!logFile.exists())
                    {
                        try
                        {
                            logFile.createNewFile();
                            //mFileName.createNewFile();
                        }
                        catch (IOException e)
                        {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }

                    stopbtn.setEnabled(true);
                    startbtn.setEnabled(false);
                    playbtn.setEnabled(false);
                    stopplay.setEnabled(false);
                    isRecording = true;

                    startRecording();

                    recordingThread = new Thread(new Runnable() {
                        public void run() {
                            //writeAudioDataToFile(logFile, mFileName);
                            writeAudioDataToFile(logFile);
                            //writeAudioDataToFile();
                        }
                    }, "AudioRecorder Thread");
                    recordingThread.start();



                    recordingThread1 = new Thread(new Runnable() {
                        public void run() {
                            //writeAudioDataToFile(logFile, mFileName);
                            //writeAudioDataToFile(logFile);
                            writeAudioDataToFile();
                        }
                    }, "AudioRecorder Thread");
                    recordingThread1.start();

                    series = new LineGraphSeries<>(getDataPoint());
                    graphView.addSeries(series);

                    graphView.getGridLabelRenderer().setLabelFormatter(new DefaultLabelFormatter(){
                        @Override
                        public String formatLabel(double value, boolean isValueX) {

                            if (isValueX) {

                                return sdf.format(new Date((long)value));
                            }
                            else {
                                return super.formatLabel(value, isValueX);
                            }
                        }
                    });

                    //graphView.getGridLabelRenderer().setHumanRounding(false);
                    graphView.getGridLabelRenderer().setNumHorizontalLabels(5);
                }
                else
                {
                    RequestPermissions();
                }
            }
        });

        stopbtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //isActive = false;
                stopbtn.setEnabled(false);
                startbtn.setEnabled(true);
                playbtn.setEnabled(true);
                stopplay.setEnabled(true);

                try {
                    stopRecording();
                } catch (IOException e) {
                    e.printStackTrace();
                }


                Toast.makeText(getActivity().getApplicationContext(), "Recording Stopped", Toast.LENGTH_LONG).show();
            }
        });
        playbtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopbtn.setEnabled(false);
                startbtn.setEnabled(true);
                playbtn.setEnabled(false);
                stopplay.setEnabled(true);

                try {
                    PlayShortAudioFileViaAudioTrack("/sdcard/8k16bitMono.pcm");
                } catch (IOException e) {
                    e.printStackTrace();
                }

            }
        });
        stopplay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //mPlayer.release();
                //mPlayer = null;
                stopbtn.setEnabled(false);
                startbtn.setEnabled(true);
                playbtn.setEnabled(true);
                stopplay.setEnabled(false);
                Toast.makeText(getActivity().getApplicationContext(),"Playing Audio Stopped", Toast.LENGTH_SHORT).show();
            }
        });

        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        //menu.findItem(R.id.hex).setChecked(hexEnabled);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        }
        else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        }
        /*else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        }*/
        else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                data = (str + newline).getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg+'\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void receive(byte[] data) {
        if(hexEnabled) {
            receiveText.append(TextUtil.toHexString(data) + '\n');
        } else {
            String msg = new String(data);
            String tempmsg = msg;
            if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                // don't show CR as ^M if directly before LF
                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                /*if (msg.contains("BACK")) {
                    apneaEvent = true;
                    msg += " apnea true";
                    numEvents += 1;
                }
                 */
                //double roll = 57.3 * Math.atan2(Integer.parseInt(inputInfo.get(0)), Integer.parseInt(inputInfo.get(1)));
                //int breathRate = (60 * Integer.parseInt(inputInfo.get(2))) / Integer.parseInt(inputInfo.get(2));
                //msg += "\nRoll: " + roll + "  BreathRate: " + breathRate;
                /*if (msg.contains("100")) {
                    apneaEvent = true;
                    msg += " apnea true";
                    numEvents += 1;
                }
                else {
                    //apneaEvent = false;
                }*/
                // special handling if CR and LF come in separate fragments
                if (pendingNewline && msg.charAt(0) == '\n') {
                    Editable edt = receiveText.getEditableText();
                    if (edt != null && edt.length() > 1)
                        edt.replace(edt.length() - 2, edt.length(), "");
                }
                pendingNewline = msg.charAt(msg.length() - 1) == '\r';
            }
            ArrayList<String> inputInfo = new ArrayList<>(Arrays.asList(tempmsg.split(",")));
            String in = inputInfo.get(0).replaceAll("\\s+", "");
            if (!in.equals("")) {
                switch (receiveCounter) {
                    case 0:
                        acc_y = Integer.parseInt(in);
                        receiveCounter++;
                        break;
                    case 1:
                        acc_z = Integer.parseInt(in);
                        receiveCounter++;
                        break;
                    case 2:
                        jt = Integer.parseInt(in);
                        receiveCounter++;
                        break;
                    case 3:
                        breathInt = Integer.parseInt(in);
                        if (breathInt <= 0)
                            breathInt = 1;
                        roll = 57.3 * Math.atan2(acc_y, acc_z);
                        breathRate = (60 * jt) / breathInt;
                        msg += "\nRoll: " + roll + "  BreathRate: " + breathRate;
                        receiveCounter = 0;

                        if (soundApnea && ((roll >= -45 && roll <= 45) || (roll >= 135 || roll <= -135)) && breathRate <= 8)
                            send(Integer.toString(vibSpeed));
                        else if ((roll > 45 && roll < 135) || (roll > -135 && roll < -45))
                            send(Integer.toString(0));
                        if (alarmEnable) {
                            if ((roll >= -45 && roll <= 45) || (roll >= 135 || roll <= -135)){
                                numEvents += 1;
                            }
                            if (numEvents >= 30) {
                                numEvents = 0;
                                if (breathRate <= 5) {
                                    Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                                    Ringtone ringtoneAlarm = RingtoneManager.getRingtone(getActivity().getApplicationContext(), alarmSound);
                                    ringtoneAlarm.play();
                                }
                                send(Integer.toString(vibSpeed));
                            }
                        }
                        else
                            numEvents = 0;
                        break;
                }
            }
            receiveText.append(TextUtil.toCaretString(msg, newline.length() != 0));
        }
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        receive(data);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_AUDIO_PERMISSION_CODE:
                if (grantResults.length> 0) {
                    boolean permissionToRecord = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                    boolean permissionToStore = grantResults[1] ==  PackageManager.PERMISSION_GRANTED;
                    if (permissionToRecord && permissionToStore) {
                        Toast.makeText(getActivity().getApplicationContext(), "Permission Granted", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(getActivity().getApplicationContext(),"Permission Denied",Toast.LENGTH_LONG).show();
                    }
                }
                break;
        }
    }
    public boolean CheckPermissions() {
        int result = ContextCompat.checkSelfPermission(getActivity().getApplicationContext(), WRITE_EXTERNAL_STORAGE);
        int result1 = ContextCompat.checkSelfPermission(getActivity().getApplicationContext(), RECORD_AUDIO);
        return result == PackageManager.PERMISSION_GRANTED && result1 == PackageManager.PERMISSION_GRANTED;
    }
    private void RequestPermissions() {
        ActivityCompat.requestPermissions(getActivity(), new String[]{RECORD_AUDIO, WRITE_EXTERNAL_STORAGE}, REQUEST_AUDIO_PERMISSION_CODE);
    }

    private void createAudioRecord() {

        if (mSampleRate > 0 && mAudioFormat > 0 && mChannelConfig > 0) {
            mRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, mSampleRate, mChannelConfig, mAudioFormat, mBufferSize);

            return;
        }

        // Find best/compatible AudioRecord
        for (int sampleRate : new int[] { 8000, 11025, 16000, 22050, 32000, 44100, 47250, 48000 }) {
            for (short audioFormat : new short[] { AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_8BIT }) {
                for (short channelConfig : new short[] { AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.CHANNEL_CONFIGURATION_MONO, AudioFormat.CHANNEL_CONFIGURATION_STEREO }) {

                    // Try to initialize
                    try {
                        mBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);

                        if (mBufferSize < 0) {
                            continue;
                        }

                        mBuffer = new short[mBufferSize];
                        mRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat,
                                mBufferSize);


                        if (mRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
                            mSampleRate = sampleRate;
                            mAudioFormat = audioFormat;
                            mChannelConfig = channelConfig;

                            return;
                        }

                        mRecorder.release();
                        mRecorder = null;
                    }
                    catch (Exception e) {
                        // Do nothing
                    }
                }
            }
        }

    }

    public synchronized void startRecording() {
        if (mRecorder == null || mRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
            createAudioRecord();
        }

        if (mLocks == 0) {

            mRecorder.startRecording();

        }

        mLocks++;
    }


    public synchronized void stopRecording() throws IOException {
        if (mLocks > 0)
            mLocks--;

        if (mLocks == 0) {
            if (mRecorder != null) {
                isRecording = false;
                mRecorder.stop();
                mRecorder.release();
                mRecorder = null;
                recordingThread = null;
                recordingThread1 = null;
                //PlayShortAudioFileViaAudioTrack("/sdcard/8k16bitMono.pcm");
            }
        }

        //copyWaveFile(getTempFilename(),getFilename());
        //deleteTempFile();
    }

    private int getRawAmplitude() {
        if (mRecorder == null) {
            createAudioRecord();
        }

        final int bufferReadSize = mRecorder.read(mBuffer, 0, mBufferSize);

        if (bufferReadSize < 0) {
            return 0;
        }

        int sum = 0;
        for (int i = 0; i < bufferReadSize; i++) {
            sum += Math.abs(mBuffer[i]);
        }

        if (bufferReadSize > 0) {
            return sum / bufferReadSize;
        }

        return 0;
    }

    private String getFilename(){
        String filepath = Environment.getExternalStorageDirectory().getPath();
        File file = new File(filepath,AUDIO_RECORDER_FOLDER);

        if (!file.exists()) {
            file.mkdirs();
        }

        return (file.getAbsolutePath() + "/" + System.currentTimeMillis() +
                AUDIO_RECORDER_FILE_EXT_WAV);
    }

    private String getTempFilename() {
        String filepath = Environment.getExternalStorageDirectory().getPath();
        File file = new File(filepath,AUDIO_RECORDER_FOLDER);

        if (!file.exists()) {
            file.mkdirs();
        }

        File tempFile = new File(filepath,AUDIO_RECORDER_TEMP_FILE);

        if (tempFile.exists())
            tempFile.delete();

        return (file.getAbsolutePath() + "/" + AUDIO_RECORDER_TEMP_FILE);
    }



    private void writeAudioDataToFile(File logFile) {

        short sData[] = new short[mBufferSize];

        //Write the output audio in byte

        while(isRecording) {

            int valBuff = mRecorder.read(sData, 0, mBufferSize);

            String sDataString = "";
            //sDataString = "";
            double sDataMax = 0;

            if (valBuff < 0) {
                sDataMax = 0;

                sDataString += "\n" + sDataMax;
            }

            int sum = 0;
            for(int i=0; i<sData.length; i++){

                if(Math.abs(sData[i])>=sDataMax){
                    sDataMax = Math.abs(sData[i]);
                    sum += sDataMax;
                }
                //sDataString += "\n" + sDataMax;
            }

            if (valBuff > 0) {
                int ans =  sum / valBuff;
                float Ampans = (float) (MAX_REPORTABLE_DB + (20 * Math.log10(ans / MAX_REPORTABLE_AMP)));

                /*

                if (Ampans >= 27.0 && Ampans <= 33.0 && snoringState > 0) {

                    state.setText(" This is a sleep apnea state. Need to check data!");
                    snoringState++;
                }

                if (Ampans >= 27.0 && Ampans <= 33.0 && snoringState <= 0) {

                    state.setText(" This is a normal snoring state. NO Need to check data!");
                }

                if (Ampans >= 15.0 && Ampans <27) {

                    state.setText(" This is a normal snoring state. NO Need to check data!");
                    snoringState--;
                }

                if ( Ampans < 15) {

                    state.setText(" This is a normal state. Need to check data!");
                }

                if ( Ampans > 40) {

                    state.setText(" This is a chronic snoring state");
                }

                 */

                if (Ampans >= 27.0 ) {
                    if (Ampans > 40) {
                        state.setText(" This is a chronic snoring state");
                    }
                    if (Ampans < 37 && Ampans > 30){
                        soundApnea = true;
                        state.setText(" This is a sleep apnea state. Need to check data!");
                    }
                }

                if (Ampans < 27.0) {

                    if (timeVar > 0) {

                        state.setText(" This is a sleep apnea state. Need to check data!");
                        timeVar--;
                    }

                    else {
                        soundApnea = false;
                        state.setText(" This is a normal snoring state. NO Need to check data!");
                        timeVar = 15;
                    }

                }

                sDataString += "\n" + Ampans;

            }

            dispAmpl.setText("The value of amp is: " + sDataString);


            try {

                Calendar cal = Calendar.getInstance();
                //SimpleDateFormat df = new SimpleDateFormat("ddMMMyyyy_HHmmss");
                sdf = new SimpleDateFormat("HH:mm:ss");
                String formattedDate1 = sdf.format(cal.getTime());

                BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
                buf.append(formattedDate1);
                //buf.newLine();
                buf.append(sDataString);
                buf.newLine();
                buf.close();

            } catch (IOException e) {
                e.printStackTrace();
            }
        }



    }

    private void dispAndWrite(int valBuff, short sData[]) {

        String sDataString = "";
        //sDataString = "";
        double sDataMax = 0;

        if (valBuff < 0) {
            sDataMax = 0;

            sDataString += "\n" + sDataMax;
        }

        int sum = 0;
        for(int i=0; i<sData.length; i++){

            if(Math.abs(sData[i])>=sDataMax){
                sDataMax = Math.abs(sData[i]);
                sum += sDataMax;
            }
            //sDataString += "\n" + sDataMax;
        }

        if (valBuff > 0) {
            int ans =  sum / valBuff;
            float Ampans = (float) (MAX_REPORTABLE_DB + (20 * Math.log10(ans / MAX_REPORTABLE_AMP)));
            sDataString += "\n" + Ampans;

        }

        dispAmpl.setText("The value of amp is: " + sDataString);

    }



    private void writeAudioDataToFile() {
        //Write the output audio in byte
        String filePath = "/sdcard/8k16bitMono.pcm";
        byte saudioBuffer[] = new byte[mBufferSize];

        FileOutputStream os = null;
        try {
            os = new FileOutputStream(filePath);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        while (isRecording) {

            // gets the voice output from microphone to byte format
            mRecorder.read(saudioBuffer, 0, mBufferSize);

            try {
                //  writes the data to file from buffer stores the voice buffer
                os.write(saudioBuffer, 0, mBufferSize);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    private void PlayShortAudioFileViaAudioTrack(String filePath) throws IOException{
        // We keep temporarily filePath globally as we have only two sample sounds now..
        if (filePath==null) {
            dispAmpl.setText("Returning null value");
            return;
        }

        //Reading the file..
        File file = new File(filePath); // for ex. path= "/sdcard/samplesound.pcm" or "/sdcard/samplesound.wav"
        byte[] byteData = new byte[(int) file.length()];
        Log.d(TAG, (int) file.length()+"");

        FileInputStream in = null;
        try {
            in = new FileInputStream( file );
            in.read( byteData );
            in.close();
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        // Set and push to audio track..
        int intSize = android.media.AudioTrack.getMinBufferSize(RECORDER_SAMPLERATE, RECORDER_CHANNELS_OUT, RECORDER_AUDIO_ENCODING);
        Log.d(TAG, intSize+"");

        AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC, RECORDER_SAMPLERATE, RECORDER_CHANNELS_OUT, RECORDER_AUDIO_ENCODING, intSize, AudioTrack.MODE_STREAM);
        if (at!=null) {
            at.play();
            // Write the byte array to the track
            at.write(byteData, 0, byteData.length);
            at.stop();
            at.release();
        }
        else
            Log.d(TAG, "audio track is not initialised ");

    }

    private DataPoint[] getDataPoint() {


        DataPoint[] dp =  {


                new DataPoint(new Date().getTime(), 1),
                new DataPoint(new Date().getTime(), 2),
                new DataPoint(new Date().getTime(), 3),
                new DataPoint(new Date().getTime(), 4),
                new DataPoint(new Date().getTime(), 5),
                new DataPoint(new Date().getTime(), 6),
                new DataPoint(new Date().getTime(), 7),
                new DataPoint(new Date().getTime(), 8),
                new DataPoint(new Date().getTime(), 9),
                new DataPoint(new Date().getTime(), 10),
                new DataPoint(new Date().getTime(), 11),
                new DataPoint(new Date().getTime(), 12),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 28),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 44),
                new DataPoint(new Date().getTime(), 46),
                new DataPoint(new Date().getTime(), 47),
                new DataPoint(new Date().getTime(), 39),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 37),
                new DataPoint(new Date().getTime(), 32),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 22),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 14),
                new DataPoint(new Date().getTime(), 15),
                new DataPoint(new Date().getTime(), 16),
                new DataPoint(new Date().getTime(), 17),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 20),

                new DataPoint(new Date().getTime(), 1),
                new DataPoint(new Date().getTime(), 2),
                new DataPoint(new Date().getTime(), 3),
                new DataPoint(new Date().getTime(), 4),
                new DataPoint(new Date().getTime(), 5),
                new DataPoint(new Date().getTime(), 6),
                new DataPoint(new Date().getTime(), 7),
                new DataPoint(new Date().getTime(), 8),
                new DataPoint(new Date().getTime(), 9),
                new DataPoint(new Date().getTime(), 10),
                new DataPoint(new Date().getTime(), 11),
                new DataPoint(new Date().getTime(), 12),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 28),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 44),
                new DataPoint(new Date().getTime(), 46),
                new DataPoint(new Date().getTime(), 47),
                new DataPoint(new Date().getTime(), 39),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 37),
                new DataPoint(new Date().getTime(), 32),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 22),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 14),
                new DataPoint(new Date().getTime(), 15),
                new DataPoint(new Date().getTime(), 16),
                new DataPoint(new Date().getTime(), 17),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 20),

                new DataPoint(new Date().getTime(), 1),
                new DataPoint(new Date().getTime(), 2),
                new DataPoint(new Date().getTime(), 3),
                new DataPoint(new Date().getTime(), 4),
                new DataPoint(new Date().getTime(), 5),
                new DataPoint(new Date().getTime(), 6),
                new DataPoint(new Date().getTime(), 7),
                new DataPoint(new Date().getTime(), 8),
                new DataPoint(new Date().getTime(), 9),
                new DataPoint(new Date().getTime(), 10),
                new DataPoint(new Date().getTime(), 11),
                new DataPoint(new Date().getTime(), 12),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 28),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 44),
                new DataPoint(new Date().getTime(), 46),
                new DataPoint(new Date().getTime(), 47),
                new DataPoint(new Date().getTime(), 39),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 37),
                new DataPoint(new Date().getTime(), 32),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 22),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 14),
                new DataPoint(new Date().getTime(), 15),
                new DataPoint(new Date().getTime(), 16),
                new DataPoint(new Date().getTime(), 17),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 20),

                new DataPoint(new Date().getTime(), 1),
                new DataPoint(new Date().getTime(), 2),
                new DataPoint(new Date().getTime(), 3),
                new DataPoint(new Date().getTime(), 4),
                new DataPoint(new Date().getTime(), 5),
                new DataPoint(new Date().getTime(), 6),
                new DataPoint(new Date().getTime(), 7),
                new DataPoint(new Date().getTime(), 8),
                new DataPoint(new Date().getTime(), 9),
                new DataPoint(new Date().getTime(), 10),
                new DataPoint(new Date().getTime(), 11),
                new DataPoint(new Date().getTime(), 12),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 28),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 44),
                new DataPoint(new Date().getTime(), 46),
                new DataPoint(new Date().getTime(), 47),
                new DataPoint(new Date().getTime(), 39),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 37),
                new DataPoint(new Date().getTime(), 32),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 22),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 14),
                new DataPoint(new Date().getTime(), 15),
                new DataPoint(new Date().getTime(), 16),
                new DataPoint(new Date().getTime(), 17),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 20),

                new DataPoint(new Date().getTime(), 1),
                new DataPoint(new Date().getTime(), 2),
                new DataPoint(new Date().getTime(), 3),
                new DataPoint(new Date().getTime(), 4),
                new DataPoint(new Date().getTime(), 5),
                new DataPoint(new Date().getTime(), 6),
                new DataPoint(new Date().getTime(), 7),
                new DataPoint(new Date().getTime(), 8),
                new DataPoint(new Date().getTime(), 9),
                new DataPoint(new Date().getTime(), 10),
                new DataPoint(new Date().getTime(), 11),
                new DataPoint(new Date().getTime(), 12),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 28),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 44),
                new DataPoint(new Date().getTime(), 46),
                new DataPoint(new Date().getTime(), 47),
                new DataPoint(new Date().getTime(), 39),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 37),
                new DataPoint(new Date().getTime(), 32),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 22),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 14),
                new DataPoint(new Date().getTime(), 15),
                new DataPoint(new Date().getTime(), 16),
                new DataPoint(new Date().getTime(), 17),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 20),

                new DataPoint(new Date().getTime(), 1),
                new DataPoint(new Date().getTime(), 2),
                new DataPoint(new Date().getTime(), 3),
                new DataPoint(new Date().getTime(), 4),
                new DataPoint(new Date().getTime(), 5),
                new DataPoint(new Date().getTime(), 6),
                new DataPoint(new Date().getTime(), 7),
                new DataPoint(new Date().getTime(), 8),
                new DataPoint(new Date().getTime(), 9),
                new DataPoint(new Date().getTime(), 10),
                new DataPoint(new Date().getTime(), 11),
                new DataPoint(new Date().getTime(), 12),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 28),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 29),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 31),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 30),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 26),
                new DataPoint(new Date().getTime(), 27),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 21),
                new DataPoint(new Date().getTime(), 23),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 34),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 41),
                new DataPoint(new Date().getTime(), 44),
                new DataPoint(new Date().getTime(), 46),
                new DataPoint(new Date().getTime(), 47),
                new DataPoint(new Date().getTime(), 39),
                new DataPoint(new Date().getTime(), 38),
                new DataPoint(new Date().getTime(), 37),
                new DataPoint(new Date().getTime(), 32),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 22),
                new DataPoint(new Date().getTime(), 24),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 25),
                new DataPoint(new Date().getTime(), 13),
                new DataPoint(new Date().getTime(), 14),
                new DataPoint(new Date().getTime(), 15),
                new DataPoint(new Date().getTime(), 16),
                new DataPoint(new Date().getTime(), 17),
                new DataPoint(new Date().getTime(), 18),
                new DataPoint(new Date().getTime(), 19),
                new DataPoint(new Date().getTime(), 20),


                /*
                new DataPoint(new Date().getTime(), AmpansArr.get(0)),
                new DataPoint(new Date().getTime(), AmpansArr.get(1)),
                new DataPoint(new Date().getTime(), AmpansArr.get(2)),
                new DataPoint(new Date().getTime(), AmpansArr.get(3)),
                new DataPoint(new Date().getTime(), AmpansArr.get(4)),
                new DataPoint(new Date().getTime(), AmpansArr.get(5)),
                new DataPoint(new Date().getTime(), AmpansArr.get(6)),
                new DataPoint(new Date().getTime(), AmpansArr.get(7)),
                new DataPoint(new Date().getTime(), AmpansArr.get(8)),
                new DataPoint(new Date().getTime(), AmpansArr.get(9)),
                new DataPoint(new Date().getTime(), AmpansArr.get(10)),
                new DataPoint(new Date().getTime(), AmpansArr.get(11)),
                new DataPoint(new Date().getTime(), AmpansArr.get(12)),
                new DataPoint(new Date().getTime(), AmpansArr.get(13)),
                new DataPoint(new Date().getTime(), AmpansArr.get(14)),
                new DataPoint(new Date().getTime(), AmpansArr.get(15)),
                new DataPoint(new Date().getTime(), AmpansArr.get(16)),
                new DataPoint(new Date().getTime(), AmpansArr.get(17)),
                new DataPoint(new Date().getTime(), AmpansArr.get(18)),
                new DataPoint(new Date().getTime(), AmpansArr.get(19)),
                new DataPoint(new Date().getTime(), AmpansArr.get(20)),
                new DataPoint(new Date().getTime(), AmpansArr.get(21)),
                new DataPoint(new Date().getTime(), AmpansArr.get(22)),
                new DataPoint(new Date().getTime(), AmpansArr.get(23)),
                new DataPoint(new Date().getTime(), AmpansArr.get(24)),
                new DataPoint(new Date().getTime(), AmpansArr.get(25)),
                new DataPoint(new Date().getTime(), AmpansArr.get(26)),
                new DataPoint(new Date().getTime(), AmpansArr.get(27)),
                new DataPoint(new Date().getTime(), AmpansArr.get(28)),
                new DataPoint(new Date().getTime(), AmpansArr.get(29)),
                new DataPoint(new Date().getTime(), AmpansArr.get(30)),
                new DataPoint(new Date().getTime(), AmpansArr.get(31)),
                new DataPoint(new Date().getTime(), AmpansArr.get(32)),
                new DataPoint(new Date().getTime(), AmpansArr.get(33)),
                new DataPoint(new Date().getTime(), AmpansArr.get(34)),
                new DataPoint(new Date().getTime(), AmpansArr.get(35)),
                new DataPoint(new Date().getTime(), AmpansArr.get(36)),
                new DataPoint(new Date().getTime(), AmpansArr.get(37)),
                new DataPoint(new Date().getTime(), AmpansArr.get(38)),
                new DataPoint(new Date().getTime(), AmpansArr.get(39)),
                new DataPoint(new Date().getTime(), AmpansArr.get(40)),
                new DataPoint(new Date().getTime(), AmpansArr.get(41)),
                new DataPoint(new Date().getTime(), AmpansArr.get(42)),
                new DataPoint(new Date().getTime(), AmpansArr.get(43)),
                new DataPoint(new Date().getTime(), AmpansArr.get(44)),
                new DataPoint(new Date().getTime(), AmpansArr.get(45)),
                new DataPoint(new Date().getTime(), AmpansArr.get(46)),
                new DataPoint(new Date().getTime(), AmpansArr.get(47)),
                new DataPoint(new Date().getTime(), AmpansArr.get(48)),
                new DataPoint(new Date().getTime(), AmpansArr.get(49)),
                new DataPoint(new Date().getTime(), AmpansArr.get(50)),
                new DataPoint(new Date().getTime(), AmpansArr.get(51)),
                new DataPoint(new Date().getTime(), AmpansArr.get(52)),
                new DataPoint(new Date().getTime(), AmpansArr.get(53)),
                new DataPoint(new Date().getTime(), AmpansArr.get(54)) ,
                new DataPoint(new Date().getTime(), AmpansArr.get(55)),
                new DataPoint(new Date().getTime(), AmpansArr.get(56)),
                new DataPoint(new Date().getTime(), AmpansArr.get(57)),
                new DataPoint(new Date().getTime(), AmpansArr.get(58)),
                new DataPoint(new Date().getTime(), AmpansArr.get(59)),
                new DataPoint(new Date().getTime(), AmpansArr.get(60)),
                new DataPoint(new Date().getTime(), AmpansArr.get(61)),
                new DataPoint(new Date().getTime(), AmpansArr.get(62)),
                new DataPoint(new Date().getTime(), AmpansArr.get(63)),
                new DataPoint(new Date().getTime(), AmpansArr.get(64)),
                new DataPoint(new Date().getTime(), AmpansArr.get(65)),
                new DataPoint(new Date().getTime(), AmpansArr.get(66)),
                new DataPoint(new Date().getTime(), AmpansArr.get(67)),
                new DataPoint(new Date().getTime(), AmpansArr.get(68)),
                new DataPoint(new Date().getTime(), AmpansArr.get(69)),
                new DataPoint(new Date().getTime(), AmpansArr.get(70)),
                new DataPoint(new Date().getTime(), AmpansArr.get(71)),
                new DataPoint(new Date().getTime(), AmpansArr.get(72)),
                new DataPoint(new Date().getTime(), AmpansArr.get(73)),
                new DataPoint(new Date().getTime(), AmpansArr.get(74)),
                new DataPoint(new Date().getTime(), AmpansArr.get(75)),
                new DataPoint(new Date().getTime(), AmpansArr.get(76)),
                new DataPoint(new Date().getTime(), AmpansArr.get(77)),
                new DataPoint(new Date().getTime(), AmpansArr.get(78)),
                new DataPoint(new Date().getTime(), AmpansArr.get(79)),
                new DataPoint(new Date().getTime(), AmpansArr.get(80)),
                new DataPoint(new Date().getTime(), AmpansArr.get(81)),
                new DataPoint(new Date().getTime(), AmpansArr.get(82)),
                new DataPoint(new Date().getTime(), AmpansArr.get(83)),
                new DataPoint(new Date().getTime(), AmpansArr.get(84)),
                new DataPoint(new Date().getTime(), AmpansArr.get(85)),
                new DataPoint(new Date().getTime(), AmpansArr.get(86)),
                new DataPoint(new Date().getTime(), AmpansArr.get(87)),
                new DataPoint(new Date().getTime(), AmpansArr.get(88)),
                new DataPoint(new Date().getTime(), AmpansArr.get(89)),
                new DataPoint(new Date().getTime(), AmpansArr.get(90)),
                new DataPoint(new Date().getTime(), AmpansArr.get(91)),
                new DataPoint(new Date().getTime(), AmpansArr.get(92)),
                new DataPoint(new Date().getTime(), AmpansArr.get(93)),
                new DataPoint(new Date().getTime(), AmpansArr.get(94)),
                new DataPoint(new Date().getTime(), AmpansArr.get(95)),
                new DataPoint(new Date().getTime(), AmpansArr.get(96)),
                new DataPoint(new Date().getTime(), AmpansArr.get(97)),
                new DataPoint(new Date().getTime(), AmpansArr.get(98)),
                new DataPoint(new Date().getTime(), AmpansArr.get(99))

                 */


        };

        return dp;

    }


}


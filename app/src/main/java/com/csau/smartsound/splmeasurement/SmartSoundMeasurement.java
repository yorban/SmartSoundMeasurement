// Copyright 2011 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.csau.smartsound.splmeasurement;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.opencsv.CSVWriter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;

/**
 * This is the main activity of the SPL meter.
 * <p/>
 * This class owns a micInput object that encapsulates the audio input
 * stream. The micInput object calls the processAudioFrame method which
 * computes the RMS value etc. and the display is updated.
 */
public class SmartSoundMeasurement extends Activity implements
        MicrophoneInputListener {

    MicrophoneInput micInput;  // The micInput object provides real time audio.
    TextView mdBTextView;
    TextView mdBFractionTextView;
    BarLevelDrawable mBarLevel;
    private TextView mGainTextView;

    double mOffsetdB = 10;  // Offset for bar, i.e. 0 lit LEDs at 10 dB.
    // The Google ASR input requirements state that audio input sensitivity
    // should be set such that 90 dB SPL at 1000 Hz yields RMS of 2500 for
    // 16-bit samples, i.e. 20 * log_10(2500 / mGain) = 90.
    double mGain = 2500.0 / Math.pow(10.0, 90.0 / 20.0);
    // For displaying error in calibration.
    double mDifferenceFromNominal = 0.0;
    double mRmsSmoothed;  // Temporally filtered version of RMS.
    double mAlpha = 0.9;  // Coefficient of IIR smoothing filter for RMS.
    private int mSampleRate;  // The audio sampling rate to use.
    private int mAudioSource;  // The audio source to use.
    String status;

    static final int EMAIL_REQUEST = 0;

    // Variables to monitor UI update and check for slow updates.
    private volatile boolean mDrawing;
    private volatile int mDrawingCollided;

    private static final String TAG = "SmartSoundMeasurementActivity";

    private CSVWriter writer;
    private EditText labelText;

    private File output;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        output = null;


        // Here the micInput object is created for audio capture.
        // It is set up to call this object to handle real time audio frames of
        // PCM samples. The incoming frames will be handled by the
        // processAudioFrame method below.
        micInput = new MicrophoneInput(this);

        // Read the layout and construct.
        setContentView(R.layout.main);

        // Get a handle that will be used in async thread post to update the
        // display.
        mBarLevel = (BarLevelDrawable) findViewById(R.id.bar_level_drawable_view);
        mdBTextView = (TextView) findViewById(R.id.dBTextView);
        mdBFractionTextView = (TextView) findViewById(R.id.dBFractionTextView);
        mGainTextView = (TextView) findViewById(R.id.gain);
        labelText = (EditText) findViewById(R.id.labelText);

        // Toggle Button handler.

        final ToggleButton onOffButton = (ToggleButton) findViewById(
                R.id.on_off_toggle_button);

        final ToggleButton loggingButton = (ToggleButton) findViewById(R.id.loggingToggleButton);

        ToggleButton.OnClickListener loggingListener =
                new ToggleButton.OnClickListener() {
                    public void onClick(View v) {
                        if (loggingButton.isChecked()) {
                            //Start logging
                            writer = null;
                            try {
                                status = labelText.getText().toString();
                                if(status.equals("")) {
                                    status = "defaultStatus";
                                }
                                if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))
                                {
                                    Toast.makeText(getBaseContext(), "No external storage for output found", Toast.LENGTH_SHORT);
                                } else {
                                    File outputDir = new File(
                                            Environment.getExternalStorageDirectory(),
                                            //Loose convention inferred from app examples
                                            "Android/data/com.csau.smartsound/splmeasurement");

                                    if (!outputDir.exists()) outputDir.mkdirs();
                                    output = new File(outputDir, status + " -- " + System.currentTimeMillis() + ".csv");
                                    writer = new CSVWriter(new FileWriter(output), ',');
                                    String[] entries = "soundLevel#status".split("#"); // array of your values
                                    writer.writeNext(entries);
                                }
                            } catch (IOException e) {
                                //error
                            }
                            if (!onOffButton.isChecked()) onOffButton.performClick();
                        } else {
                            if (onOffButton.isChecked()) onOffButton.performClick();
                            try {
                                if(writer != null) {
                                    writer.close();
                                    Intent i = new Intent(Intent.ACTION_SEND);
                                    i.setType("message/rfc822");
                                    i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(output));
                                    //i.putExtra(Intent.EXTRA_EMAIL, new String[] { "email@email.com" });
                                    i.putExtra(Intent.EXTRA_SUBJECT, output.getName());
                                    i.putExtra(Intent.EXTRA_TEXT, "Sound level measurements for activity: " + status);
                                    startActivityForResult(Intent.createChooser(i, "E-mail"), EMAIL_REQUEST);
                                } else Toast.makeText(getBaseContext(), "No CSV writer is declared", Toast.LENGTH_SHORT);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                };
        loggingButton.setOnClickListener(loggingListener);


        ToggleButton.OnClickListener tbListener =
                new ToggleButton.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (onOffButton.isChecked()) {
                            readPreferences();
                            micInput.setSampleRate(mSampleRate);
                            micInput.setAudioSource(mAudioSource);
                            micInput.start();
                        } else {
                            micInput.stop();
                        }
                    }
                };
        onOffButton.setOnClickListener(tbListener);

        // Level adjustment buttons.

        // Minus 5 dB button event handler.
        Button minus5dbButton = (Button) findViewById(R.id.minus_5_db_button);
        DbClickListener minus5dBButtonListener = new DbClickListener(-5.0);
        minus5dbButton.setOnClickListener(minus5dBButtonListener);

        // Minus 1 dB button event handler.
        Button minus1dbButton = (Button) findViewById(R.id.minus_1_db_button);
        DbClickListener minus1dBButtonListener = new DbClickListener(-1.0);
        minus1dbButton.setOnClickListener(minus1dBButtonListener);

        // Plus 1 dB button event handler.
        Button plus1dbButton = (Button) findViewById(R.id.plus_1_db_button);
        DbClickListener plus1dBButtonListener = new DbClickListener(1.0);
        plus1dbButton.setOnClickListener(plus1dBButtonListener);

        // Plus 5 dB button event handler.
        Button plus5dbButton = (Button) findViewById(R.id.plus_5_db_button);
        DbClickListener plus5dBButtonListener = new DbClickListener(5.0);
        plus5dbButton.setOnClickListener(plus5dBButtonListener);


        // Settings button, launches the settings dialog.

        Button settingsButton = (Button) findViewById(R.id.settingsButton);
        Button.OnClickListener settingsBtnListener =
                new Button.OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        final ToggleButton onOffButton = (ToggleButton) findViewById(
                                R.id.on_off_toggle_button);
                        onOffButton.setChecked(false);
                        SmartSoundMeasurement.this.micInput.stop();
                        if (loggingButton.isChecked()) loggingButton.performClick();

                        Intent settingsIntent = new Intent(SmartSoundMeasurement.this,
                                Settings.class);
                        SmartSoundMeasurement.this.startActivity(settingsIntent);
                    }
                };
        settingsButton.setOnClickListener(settingsBtnListener);
    }

    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
//        if (requestCode == EMAIL_REQUEST) {
//            final Handler handler = new Handler();
//            handler.postDelayed(new Runnable() {
//                @Override
//                public void run() {
//                    output.delete();
//                    output = null;
//                }
//            }, 10000);
//        }
    }

    /**
     * Inner class to handle press of gain adjustment buttons.
     */
    private class DbClickListener implements Button.OnClickListener {
        private double gainIncrement;

        public DbClickListener(double gainIncrement) {
            this.gainIncrement = gainIncrement;
        }

        @Override
        public void onClick(View v) {
            SmartSoundMeasurement.this.mGain *= Math.pow(10, gainIncrement / 20.0);
            mDifferenceFromNominal -= gainIncrement;
            DecimalFormat df = new DecimalFormat("##.# dB");
            mGainTextView.setText(df.format(mDifferenceFromNominal));
        }
    }

    /**
     * Method to read the sample rate and audio source preferences.
     */
    private void readPreferences() {
        SharedPreferences preferences = getSharedPreferences("SmartSoundMeasurement",
                MODE_PRIVATE);
        mSampleRate = preferences.getInt("SampleRate", 8000);
        mAudioSource = preferences.getInt("AudioSource",
                MediaRecorder.AudioSource.VOICE_RECOGNITION);
    }

    /**
     * This method gets called by the micInput object owned by this activity.
     * It first computes the RMS value and then it sets up a bit of
     * code/closure that runs on the UI thread that does the actual drawing.
     */
    @Override
    public void processAudioFrame(short[] audioFrame) {
        if (!mDrawing) {
            mDrawing = true;
            // Compute the RMS value. (Note that this does not remove DC).
            double rms = 0;
            for (short anAudioFrame : audioFrame) {
                rms += anAudioFrame * anAudioFrame;
            }
            rms = Math.sqrt(rms / audioFrame.length);

            // Compute a smoothed version for less flickering of the display.
            mRmsSmoothed = mRmsSmoothed * mAlpha + (1 - mAlpha) * rms;
            final double rmsdB = 20.0 * Math.log10(mGain * mRmsSmoothed);
            final double rmsdBActual = 20.0 * Math.log10(mGain*rms);
            Log.v(TAG, Double.toString(20 + rmsdBActual));
            final ToggleButton loggingBtn = (ToggleButton) findViewById(R.id.loggingToggleButton);
            if (loggingBtn.isChecked() && writer != null) {
                status = labelText.getText().toString();
                if(status.equals("")) {
                    status = "defaultStatus";
                }
                String[] entries = (20 + rmsdBActual + "#" + status).split("#"); // array of your values
                writer.writeNext(entries);
            }
            // Set up a method that runs on the UI thread to update of the LED bar
            // and numerical display.
            mBarLevel.post(new Runnable() {
                @Override
                public void run() {
                    // The bar has an input range of [0.0 ; 1.0] and 10 segments.
                    // Each LED corresponds to 6 dB.
                    mBarLevel.setLevel((mOffsetdB + rmsdB) / 60);

                    DecimalFormat df = new DecimalFormat("##");
                    mdBTextView.setText(df.format(20 + rmsdB));

                    int one_decimal = (int) (Math.round(Math.abs(rmsdB * 10))) % 10;
                    mdBFractionTextView.setText(Integer.toString(one_decimal));
                    mDrawing = false;
                }
            });
        } else {
            mDrawingCollided++;
            Log.v(TAG, "Level bar update collision, i.e. update took longer " +
                    "than 20ms. Collision count" + Double.toString(mDrawingCollided));
        }
    }
}

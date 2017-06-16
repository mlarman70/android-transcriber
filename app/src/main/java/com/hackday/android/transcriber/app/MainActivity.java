package com.hackday.android.transcriber.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatButton;
import android.support.v7.widget.AppCompatTextView;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.sql.Time;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String LOG_TAG = "MainActivity";

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private static String mFileName = null;

    private RecordButton mRecordButton = null;
    /*
        private MediaRecorder mRecorder = null;
    */
    private ExtAudioRecorder eaRecorder = null;

    private PlayButton mPlayButton = null;
    private ChunkCounter mChunkCounter = null;

    private int countOfChunks = 0;

    private MediaPlayer mPlayer = null;

    private TextView mTextView = null;
    private TextUpdater mTextUpdater = null;

    // Requesting permission to RECORD_AUDIO
    private boolean permissionToRecordAccepted = false;
    private String[] permissions = {Manifest.permission.RECORD_AUDIO};

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQUEST_RECORD_AUDIO_PERMISSION:
                permissionToRecordAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                break;
        }
        if (!permissionToRecordAccepted) finish();

    }

    private void onRecord(boolean start) {
        if (start) {
            startRecording();
        } else {
            stopRecording();
        }
    }

    private void onPlay(boolean start) {
        if (start) {
            startPlaying();
        } else {
            stopPlaying();
        }
    }

    private void startPlaying() {
        mRecordButton.setEnabled(false);

        mPlayer = new MediaPlayer();
        try {
            mPlayer.setDataSource(mFileName);
            mPlayer.prepare();
            mPlayer.start();
        } catch (IOException e) {
            Log.e(LOG_TAG, "prepare() failed");
        }
    }

    private void stopPlaying() {
        mRecordButton.setEnabled(true);

        mPlayer.release();
        mPlayer = null;
    }

    private void startRecording() {
        mPlayButton.setEnabled(false);

        eaRecorder = ExtAudioRecorder.getInstance(ExtAudioRecorder.RECORDING_UNCOMPRESSED);
        eaRecorder.addChunkListener(new ExtAudioRecorder.ChunkListener() {
            @Override
            public void onChunk(@NotNull File chunkFile) {
                chunkArrived(chunkFile);
            }
        });

        eaRecorder.setOutputFile(mFileName);
        eaRecorder.prepare();
        eaRecorder.start();

        mTextUpdater = new TextUpdater(this, mTextView, new TextSource() {
            public List<String> readText() {
                return Arrays.asList("words", "from", "chunk", Integer.toString(countOfChunks));
            }
        });
        mTextUpdater.start();

    }

    private void stopRecording() {
        mPlayButton.setEnabled(true);

        eaRecorder.stop();
        eaRecorder.release();
        eaRecorder = null;

        mTextUpdater.stop();
        mTextUpdater = null;
    }

    private void chunkArrived(File chunkFile) {
        countOfChunks++;
        mChunkCounter.setText(Integer.toString(countOfChunks));
    }

    class RecordButton extends AppCompatButton {
        boolean mStartRecording = true;

        OnClickListener clicker = new OnClickListener() {
            public void onClick(View v) {
                onRecord(mStartRecording);
                if (mStartRecording) {
                    setText("Stop recording");
                } else {
                    setText("Start recording");
                }
                mStartRecording = !mStartRecording;
            }
        };

        public RecordButton(Context ctx) {
            super(ctx);
            setText("Start recording");
            setOnClickListener(clicker);
        }
    }

    class PlayButton extends AppCompatButton {
        boolean mStartPlaying = true;

        OnClickListener clicker = new OnClickListener() {
            public void onClick(View v) {
                onPlay(mStartPlaying);
                if (mStartPlaying) {
                    setText("Stop playing");
                } else {
                    setText("Start playing");
                }
                mStartPlaying = !mStartPlaying;
            }
        };

        public PlayButton(Context ctx) {
            super(ctx);
            setText("Start playing");
            setOnClickListener(clicker);
        }
    }

    class ChunkCounter extends AppCompatTextView {
        public ChunkCounter(Context ctx) {
            super(ctx);
            setText(Integer.toString(countOfChunks));
        }
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // Record to the external cache directory for visibility
        mFileName = getExternalCacheDir().getAbsolutePath();
        mFileName += "/audiorecordtest_9.wav";
        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION);

        LinearLayout llv = new LinearLayout(this);
        llv.setOrientation(LinearLayout.VERTICAL);

        LinearLayout llh = new LinearLayout(this);
        llh.setOrientation(LinearLayout.HORIZONTAL);

        mRecordButton = new RecordButton(this);
        llh.addView(mRecordButton,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        0));
        mPlayButton = new PlayButton(this);
        llh.addView(mPlayButton,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        0));

        mChunkCounter = new ChunkCounter(this);
        llh.addView(mChunkCounter,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        0));

        llv.addView(llh);

        mTextView = new AppCompatTextView(this);
        mTextView.setGravity(Gravity.BOTTOM);
        mTextView.setMovementMethod(ScrollingMovementMethod.getInstance());
        llv.addView(mTextView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        0));

        setContentView(llv);

    }

    @Override
    public void onStop() {
        super.onStop();
        if (eaRecorder != null) {
            eaRecorder.release();
            eaRecorder = null;
        }
/*
        if (mRecorder != null) {
            mRecorder.release();
            mRecorder = null;
        }
*/

        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }
}

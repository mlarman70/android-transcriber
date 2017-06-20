package com.hackday.android.transcriber.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.experimental.or

class ExtAudioRecorder
/**
 * Default constructor

 * Instantiates a new recorder, in case of compressed recording the parameters can be left as 0.
 * In case of errors, no exception is thrown, but the state is set to ERROR

 */
(audioSource: Int, sampleRate: Int, channelConfig: Int, audioFormat: Int) {

    companion object {
        private val sampleRates = intArrayOf(44100, 22050, 11025, 8000)

        @JvmStatic
        fun getInstance(): ExtAudioRecorder {
            var result: ExtAudioRecorder

            var i = 0
            do {
                result = ExtAudioRecorder(AudioSource.MIC,
                        sampleRates[i],
                        AudioFormat.CHANNEL_CONFIGURATION_MONO,
                        AudioFormat.ENCODING_PCM_16BIT)

            } while ((++i < sampleRates.size) and (result.state != ExtAudioRecorder.State.INITIALIZING))

            return result
        }

        @JvmField
        val RECORDING_UNCOMPRESSED = true

        // The interval in which the recorded samples are output to the file
        // Used only in uncompressed mode
        private val TIMER_INTERVAL = 120
    }

    /**
     * INITIALIZING : recorder is initializing;
     * READY : recorder has been initialized, recorder not yet started
     * RECORDING : recording
     * ERROR : reconstruction needed
     * STOPPED: reset needed
     */
    enum class State {
        INITIALIZING, READY, RECORDING, ERROR, STOPPED
    }

    // Recorder used for uncompressed recording
    private lateinit var audioRecorder: AudioRecord

    // Stores current amplitude (only in uncompressed mode)
    private var cAmplitude = 0

    // Output file path
    private var filePath: String? = null

    // Recorder state; see State
    /**

     * Returns the state of the recorder in a RehearsalAudioRecord.State typed object.
     * Useful, as no exceptions are thrown.

     * @return recorder state
     */
    var state: State? = null
        private set

    // File writer (only in uncompressed mode)
    private lateinit var randomAccessWriter: RandomAccessFile
    private lateinit var chunkPath: File
    private var chunkCounter: Int = 0

    // Number of channels, sample rate, sample size(size in bits), buffer size, audio source, sample size(see AudioFormat)
    private var nChannels: Short = 0
    private var sRate: Int = 0
    private var bSamples: Short = 0
    private var bufferSize: Int = 0
    private var aSource: Int = 0
    private var aFormat: Int = 0

    // Number of frames written to file on each output(only in uncompressed mode)
    private var framePeriod: Int = 0

    // Buffer for output(only in uncompressed mode)
    private lateinit var buffer: ByteArray

    private val byteBuffer = ByteBuffer.allocate(320 * 1024)

    // Number of bytes written to file after header(only in uncompressed mode)
    // after stop() is called, this size is written to the header/data chunk in the wave file
    private var payloadSize: Int = 0

    private val listeners: MutableSet<ChunkListener> = mutableSetOf()

    fun addChunkListener(listener: ChunkListener) {
        listeners.add(listener)
    }

    /*
    *
    * Method used for recording.
    *
    */
    private val updateListener = object : AudioRecord.OnRecordPositionUpdateListener {
        override fun onPeriodicNotification(recorder: AudioRecord) {
            audioRecorder.read(buffer, 0, buffer.size) // Fill buffer
            val chunkFile: File? = writeToBuffer(buffer)

            chunkFile?.let {
                listeners.forEach { it.onChunk(chunkFile) }
            }

            try {
                randomAccessWriter.write(buffer) // Write buffer to file
                payloadSize += buffer.size
                if (bSamples.toInt() == 16) {
                    (0..buffer.size / 2 - 1)
                            .asSequence()
                            .map {
                                // 16bit sample size
                                getShort(buffer[it * 2], buffer[it * 2 + 1])
                            }
                            .filter { it > cAmplitude }
                            .forEach {
                                // Check amplitude
                                cAmplitude = it.toInt()
                            }
                } else { // 8bit sample size
                    buffer.indices
                            .asSequence()
                            .filter { buffer[it] > cAmplitude }
                            .forEach {
                                // Check amplitude
                                cAmplitude = buffer[it].toInt()
                            }
                }
            } catch (e: IOException) {
                Log.e(ExtAudioRecorder::class.java.name, "Error occurred in updateListener, recording is aborted")
                //stop();
            }

        }

        override fun onMarkerReached(recorder: AudioRecord) {
            // NOT USED
        }
    }

    private fun writeToBuffer(buffer: ByteArray): File? {
        val remaining = byteBuffer.remaining()

        if (buffer.size > remaining) {
            byteBuffer.put(buffer, 0, remaining)
            val chunkFile = dumpBufferToFile()

            byteBuffer.put(buffer, remaining, buffer.size - remaining)

            return chunkFile
        }

        byteBuffer.put(buffer, 0, buffer.size)

        return null
    }

    private fun dumpBufferToFile(): File {
        val chunkFile = File(chunkPath, "chunk_" + (++chunkCounter) + ".pcm16")

        byteBuffer.flip()
        FileOutputStream(chunkFile).channel.use {
            while (byteBuffer.remaining() > 0) {
                it.write(byteBuffer)
            }

            it.force(false)
        }

        byteBuffer.clear()
        return chunkFile
    }

    init {
        try {
            if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                bSamples = 16
            } else {
                bSamples = 8
            }

            if (channelConfig == AudioFormat.CHANNEL_CONFIGURATION_MONO) {
                nChannels = 1
            } else {
                nChannels = 2
            }

            aSource = audioSource
            sRate = sampleRate
            aFormat = audioFormat

            framePeriod = sampleRate * TIMER_INTERVAL / 1000
            bufferSize = framePeriod * 2 * bSamples.toInt() * nChannels.toInt() / 8
            if (bufferSize < AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)) { // Check to make sure buffer size is not smaller than the smallest allowed one
                bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                // Set frame period and timer interval accordingly
                framePeriod = bufferSize / (2 * bSamples.toInt() * nChannels.toInt() / 8)
                Log.w(ExtAudioRecorder::class.java.name, "Increasing buffer size to " + Integer.toString(bufferSize))
            }

            audioRecorder = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)

            if (audioRecorder.state != AudioRecord.STATE_INITIALIZED)
                throw Exception("AudioRecord initialization failed")
            audioRecorder.setRecordPositionUpdateListener(updateListener)
            audioRecorder.positionNotificationPeriod = framePeriod
            cAmplitude = 0
            filePath = null
            state = State.INITIALIZING

        } catch (e: Exception) {
            if (e.message != null) {
                Log.e(ExtAudioRecorder::class.java.name, e.message)
            } else {
                Log.e(ExtAudioRecorder::class.java.name, "Unknown error occurred while initializing recording")
            }
            state = State.ERROR
        }

    }

    fun setOutputFile(argPath: String) {
        if (state == State.INITIALIZING) {
            filePath = argPath
        }
    }

    /**

     * Returns the largest amplitude sampled since the last call to this method.

     * @return returns the largest amplitude since the last call, or 0 when not in recording state.
     */
    val maxAmplitude: Int
        get() {
            if (state == State.RECORDING) {
                val result = cAmplitude
                cAmplitude = 0
                return result

            } else {
                return 0
            }
        }


    /**

     * Prepares the recorder for recording, in case the recorder is not in the INITIALIZING state and the file path was not set
     * the recorder is set to the ERROR state, which makes a reconstruction necessary.
     * In case uncompressed recording is toggled, the header of the wave file is written.
     * In case of an exception, the state is changed to ERROR

     */
    fun prepare() {
        try {
            if (state == State.INITIALIZING) {
                if ((audioRecorder.state == AudioRecord.STATE_INITIALIZED) and (filePath != null)) {
                    // write file header
                    val audioFile = File(filePath)
                    chunkPath = File.createTempFile("chunks-", "", audioFile.parentFile)
                    chunkPath.delete()

                    chunkPath.mkdirs()

                    randomAccessWriter = RandomAccessFile(filePath, "rw")

                    with(randomAccessWriter) {
                        setLength(0) // Set file length to 0, to prevent unexpected behavior in case the file already existed
                        writeBytes("RIFF")
                        writeInt(0) // Final file size not known yet, write 0
                        writeBytes("WAVE")
                        writeBytes("fmt ")
                        writeInt(Integer.reverseBytes(16)) // Sub-chunk size, 16 for PCM
                        writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // AudioFormat, 1 for PCM
                        writeShort(java.lang.Short.reverseBytes(nChannels).toInt())// Number of channels, 1 for mono, 2 for stereo
                        writeInt(Integer.reverseBytes(sRate)) // Sample rate
                        writeInt(Integer.reverseBytes(sRate * bSamples.toInt() * nChannels.toInt() / 8)) // Byte rate, SampleRate*NumberOfChannels*BitsPerSample/8
                        writeShort(java.lang.Short.reverseBytes((nChannels * bSamples / 8).toShort()).toInt()) // Block align, NumberOfChannels*BitsPerSample/8
                        writeShort(java.lang.Short.reverseBytes(bSamples).toInt()) // Bits per sample
                        writeBytes("data")
                        writeInt(0) // Data chunk size not known yet, write 0
                    }
                    buffer = ByteArray(framePeriod * bSamples / 8 * nChannels)
                    state = State.READY
                } else {
                    Log.e(ExtAudioRecorder::class.java.name, "prepare() method called on uninitialized recorder")
                    state = State.ERROR
                }
            } else {
                Log.e(ExtAudioRecorder::class.java.name, "prepare() method called on illegal state")
                release()
                state = State.ERROR
            }

        } catch (e: Exception) {
            if (e.message != null) {
                Log.e(ExtAudioRecorder::class.java.name, e.message)
            } else {
                Log.e(ExtAudioRecorder::class.java.name, "Unknown error occurred in prepare()")
            }
            state = State.ERROR
        }
    }

    /**


     * Releases the resources associated with this class, and removes the unnecessary files, when necessary

     */
    fun release() {
        if (state == State.RECORDING) {
            stop()
        } else {
            if ((state == State.READY)) {
                try {
                    randomAccessWriter.close() // Remove prepared file
                } catch (e: IOException) {
                    Log.e(ExtAudioRecorder::class.java.name, "I/O exception occurred while closing output file")
                }

                File(filePath!!).delete()
            }
        }
        audioRecorder.release()
    }

    /**


     * Resets the recorder to the INITIALIZING state, as if it was just created.
     * In case the class was in RECORDING state, the recording is stopped.
     * In case of exceptions the class is set to the ERROR state.

     */
    fun reset() {
        try {
            if (state != State.ERROR) {
                release()
                filePath = null // Reset file path
                cAmplitude = 0 // Reset amplitude
                audioRecorder = AudioRecord(aSource, sRate, nChannels + 1, aFormat, bufferSize)
                state = State.INITIALIZING
            }

        } catch (e: Exception) {
            Log.e(ExtAudioRecorder::class.java.name, e.message)
            state = State.ERROR
        }

    }

    /**


     * Starts the recording, and sets the state to RECORDING.
     * Call after prepare().

     */
    fun start() {
        if (state == State.READY) {
            payloadSize = 0
            audioRecorder.startRecording()
            audioRecorder.read(buffer, 0, buffer.size)
            state = State.RECORDING
        } else {
            Log.e(ExtAudioRecorder::class.java.name, "start() called on illegal state")
            state = State.ERROR
        }
    }

    /**


     * Stops the recording, and sets the state to STOPPED.
     * In case of further usage, a reset is needed.
     * Also finalizes the wave file in case of uncompressed recording.

     */
    fun stop() {
        if (state == State.RECORDING) {
            audioRecorder.stop()

            val chunkFile = dumpBufferToFile()

            chunkFile.takeIf { it.length() > 0 }?.let { chunk ->
                listeners.forEach { it.onChunk(chunk) }
            }

            try {
                randomAccessWriter.seek(4) // Write size to RIFF header
                randomAccessWriter.writeInt(Integer.reverseBytes(36 + payloadSize))

                randomAccessWriter.seek(40) // Write size to Subchunk2Size field
                randomAccessWriter.writeInt(Integer.reverseBytes(payloadSize))

                randomAccessWriter.close()

            } catch (e: IOException) {
                Log.e(ExtAudioRecorder::class.java.name, "I/O exception occured while closing output file")
                state = State.ERROR
            }

            state = State.STOPPED
        } else {
            Log.e(ExtAudioRecorder::class.java.name, "stop() called on illegal state")
            state = State.ERROR
        }
    }

    /*
     *
     * Converts a byte[2] to a short, in LITTLE_ENDIAN format
     *
     */
    private fun getShort(argB1: Byte, argB2: Byte): Short {
        return (argB1 or (argB2.toInt().shl(8)).toByte()).toShort()
    }

    interface ChunkListener {
        fun onChunk(chunkFile: File)
    }
}
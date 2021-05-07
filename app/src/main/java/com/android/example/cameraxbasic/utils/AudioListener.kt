package com.android.example.cameraxbasic.utils

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/** Sets up a listener to listen for noise level.
 */
internal class AudioListener(cb: AudioListenerCallback) {
    @Volatile
    private var is_running = true // should be volatile, as used to communicate between threads
    private var buffer_size = -1
    private var ar // modification to ar should always be synchronized (on AudioListener.this), as the ar can be released in the AudioListener's own thread
            : AudioRecord? = null
    private var thread: Thread?

    interface AudioListenerCallback {
        fun onAudio(level: Int)
    }

    /**
     * @return Whether the audio recorder was created successfully.
     */
    fun status(): Boolean {
        var ok: Boolean
        synchronized(this@AudioListener) { ok = ar != null }
        return ok
    }

    /** Start listening.
     */
    fun start() {
        if (MyDebug.LOG) Log.d(TAG, "start")
        if (thread != null) {
            thread!!.start()
        }
    }

    /** Stop listening and release the resources.
     * @param wait_until_done If true, this method will block until the resource is freed.
     */
    fun release(wait_until_done: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "release")
            Log.d(TAG, "wait_until_done: $wait_until_done")
        }
        is_running = false
        thread = null
        if (wait_until_done) {
            if (MyDebug.LOG) Log.d(TAG, "wait until audio listener is freed")
            synchronized(this@AudioListener) {
                while (ar != null) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "ar still not freed, so wait"
                    )
                    try {
                       // this@AudioListener.wait()
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                        if (MyDebug.LOG) Log.e(
                            TAG,
                            "interrupted while waiting for audio recorder to be freed"
                        )
                    }
                }
            }
            if (MyDebug.LOG) Log.d(TAG, "audio listener is now freed")
        }
    }


    companion object {
        private const val TAG = "AudioListener"
    }

    /** Create a new AudioListener. The caller should call the start() method to start listening.
     */
    init {
        if (MyDebug.LOG) Log.d(TAG, "new AudioListener")
        val sample_rate = 8000
        val channel_config = AudioFormat.CHANNEL_IN_MONO
        val audio_format = AudioFormat.ENCODING_PCM_16BIT
        try {
            buffer_size = AudioRecord.getMinBufferSize(sample_rate, channel_config, audio_format)
            //buffer_size = -1; // test
            if (MyDebug.LOG) Log.d(
                TAG,
                "buffer_size: $buffer_size"
            )
            if (buffer_size <= 0) {
                if (MyDebug.LOG) {
                    if (buffer_size == AudioRecord.ERROR) Log.e(
                        TAG,
                        "getMinBufferSize returned ERROR"
                    ) else if (buffer_size == AudioRecord.ERROR_BAD_VALUE) Log.e(
                        TAG, "getMinBufferSize returned ERROR_BAD_VALUE"
                    )
                }
             //   return
            }
            synchronized(this@AudioListener) {
                ar = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sample_rate,
                    channel_config,
                    audio_format,
                    buffer_size
                )
              //  this@AudioListener.notifyAll() // probably not needed currently as no thread should be waiting for creation, but just for consistency
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "failed to create audiorecord")
            //return@AudioListener
        }

        // check initialised
        synchronized(this@AudioListener) {
            if (ar!!.state == AudioRecord.STATE_INITIALIZED) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "audiorecord is initialised"
                )
            } else {
                Log.e(TAG, "audiorecord failed to initialise")
                ar!!.release()
                ar = null
              //  this@AudioListener.notifyAll() // again probably not needed, but just in case
               // return
            }
        }
        val buffer = ShortArray(buffer_size)
        ar!!.startRecording()
        thread = object : Thread() {
            override fun run() {
                /*int sample_delay = (1000 * buffer_size) / sample_rate;
				if( MyDebug.LOG )
					Log.e(TAG, "sample_delay: " + sample_delay);*/
                while (is_running) {
                    /*try{
						Thread.sleep(sample_delay);
					}
					catch(InterruptedException e) {
						e.printStackTrace();
					}*/
                    try {
                        val n_read = ar!!.read(buffer, 0, buffer_size)
                        if (n_read > 0) {
                            var average_noise = 0
                            var max_noise = 0
                            for (i in 0 until n_read) {
                                val value: Int = Math.abs(buffer.get(i).toInt())
                                average_noise += value
                                max_noise = Math.max(max_noise, value)
                            }
                            average_noise /= n_read
                            /*if( MyDebug.LOG ) {
								Log.d(TAG, "n_read: " + n_read);
								Log.d(TAG, "average noise: " + average_noise);
								Log.d(TAG, "max noise: " + max_noise);
							}*/cb.onAudio(average_noise)
                        } else {
                            if (MyDebug.LOG) {
                                Log.d(TAG, "n_read: $n_read")
                                if (n_read == AudioRecord.ERROR_INVALID_OPERATION) Log.e(
                                    TAG, "read returned ERROR_INVALID_OPERATION"
                                ) else if (n_read == AudioRecord.ERROR_BAD_VALUE) Log.e(
                                    TAG, "read returned ERROR_BAD_VALUE"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        if (MyDebug.LOG) Log.e(TAG, "failed to read from audiorecord")
                    }
                }
                if (MyDebug.LOG) Log.d(TAG, "stopped running")
                synchronized(this@AudioListener) {
                    if (MyDebug.LOG) Log.d(TAG, "release ar")
                    ar!!.release()
                    ar = null
                    //this@AudioListener.notifyAll() // notify in case release() is waiting
                }
            }
        }
        // n.b., not good practice to start threads in constructors, so we require the caller to call start() instead
    }
}
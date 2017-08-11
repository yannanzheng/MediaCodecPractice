package com.evomotion.mediacodecpractice;

import android.animation.TimeAnimator;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.Toast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class MainActivity extends AppCompatActivity {
    private final String TAG = "MainActivity";
    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    @BindView(R.id.PlaybackView)
    TextureView mPlaybackView;
    private TimeAnimator mTimeAnimator = new TimeAnimator();
    private MediaExtractor mExtractor = new MediaExtractor();
    private MediaFormat mediaFormat;
    private MediaCodec decoder;
    private MediaCodecWrapper mCodecWrapper;


    private ByteBuffer[] mInputBuffers;
    private ByteBuffer[] mOutputBuffers;
    private Queue<Integer> mAvailableInputBuffers;
    private Queue<Integer> mAvailableOutputBuffers;
    private MediaCodec.BufferInfo[] mOutputBufferInfo;


    private TextureView.SurfaceTextureListener listener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            play_pause_button.setEnabled(true);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        play_pause_button.setEnabled(false);
        mPlaybackView.setSurfaceTextureListener(listener);

    }

    boolean isPlaying = false;

    @BindView(R.id.play_pause_button)
    Button play_pause_button;

    @OnClick(R.id.play_pause_button)
    public void playStateSwitch(Button button){
        if (isPlaying) {//播放状态，变成暂停
            isPlaying = false;
            play_pause_button.setText("play");
            Toast.makeText(this,"暂停播放",Toast.LENGTH_SHORT).show();
            pausePlayback();
        }else {//暂停状态，变成播放
            isPlaying = true;
            play_pause_button.setText("pause");
            Toast.makeText(this,"开始播放",Toast.LENGTH_SHORT).show();
            startPlayback();
        }
    }

    public void startPlayback(){
        {

            // Construct a URI that points to the video resource that we want to play
            Uri videoUri = Uri.parse("android.resource://"
                    + getPackageName() + "/"
                    + R.raw.vid_bigbuckbunny);

            try {

                // BEGIN_INCLUDE(initialize_extractor)
                mExtractor.setDataSource(this, videoUri, null);
                int nTracks = mExtractor.getTrackCount();

                // Begin by unselecting all of the tracks in the extractor, so we won't see
                // any tracks that we haven't explicitly selected.
                for (int i = 0; i < nTracks; ++i) {
                    mExtractor.unselectTrack(i);
                }


                // Find the first video track in the stream. In a real-world application
                // it's possible that the stream would contain multiple tracks, but this
                // sample assumes that we just want to play the first one.
                for (int i = 0; i < nTracks; ++i) {
                    // Try to create a video codec for this track. This call will return null if the
                    // track is not a video track, or not a recognized video format. Once it returns
                    // a valid MediaCodecWrapper, we can break out of the loop.
                    mCodecWrapper = MediaCodecWrapper.fromVideoFormat(mExtractor.getTrackFormat(i),
                            new Surface(mPlaybackView.getSurfaceTexture()));
                    if (mCodecWrapper != null) {
                        mExtractor.selectTrack(i);
                        break;
                    }
                }
                // END_INCLUDE(initialize_extractor)




                // By using a {@link TimeAnimator}, we can sync our media rendering commands with
                // the system display frame rendering. The animator ticks as the {@link Choreographer}
                // receives VSYNC events.
                mTimeAnimator.setTimeListener(new TimeAnimator.TimeListener() {
                    @Override
                    public void onTimeUpdate(final TimeAnimator animation,
                                             final long totalTime,
                                             final long deltaTime) {

                        boolean isEos = ((mExtractor.getSampleFlags() & MediaCodec
                                .BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                        // BEGIN_INCLUDE(write_sample)
                        if (!isEos) {
                            // Try to submit the sample to the codec and if successful advance the
                            // extractor to the next available sample to read.
                            boolean result = mCodecWrapper.writeSample(mExtractor, false,
                                    mExtractor.getSampleTime(), mExtractor.getSampleFlags());

                            if (result) {
                                // Advancing the extractor is a blocking operation and it MUST be
                                // executed outside the main thread in real applications.
                                mExtractor.advance();
                            }
                        }
                        // END_INCLUDE(write_sample)

                        // Examine the sample at the head of the queue to see if its ready to be
                        // rendered and is not zero sized End-of-Stream record.
                        MediaCodec.BufferInfo out_bufferInfo = new MediaCodec.BufferInfo();
                        mCodecWrapper.peekSample(out_bufferInfo);

                        // BEGIN_INCLUDE(render_sample)
                        if (out_bufferInfo.size <= 0 && isEos) {
                            mTimeAnimator.end();
                            mCodecWrapper.stopAndRelease();
                            mExtractor.release();
                        } else if (out_bufferInfo.presentationTimeUs / 1000 < totalTime) {
                            // Pop the sample off the queue and send it to {@link Surface}
                            mCodecWrapper.popSample(true);
                        }
                        // END_INCLUDE(render_sample)

                    }
                });

                // We're all set. Kick off the animator to process buffers and render video frames as
                // they become available
                mTimeAnimator.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }    }

    private Runnable decodeRunnable = new Runnable() {
        @Override
        public void run() {
            for (;;){
                if (!mAvailableInputBuffers.isEmpty()){
                    int index = mAvailableInputBuffers.remove();
                    ByteBuffer buffer = mInputBuffers[index];

                    int size = mExtractor.readSampleData(buffer, 0);
                    decoder.queueInputBuffer(index, 0, size, 0, 0);
                    if (!mAvailableOutputBuffers.isEmpty()) {
                        int index2 = mAvailableOutputBuffers.remove();

                        // releases the buffer back to the codec
                        decoder.releaseOutputBuffer(index2, true);
                    }


                }
            }
        }
    };

    public void pausePlayback(){

    }

    public void stopPlayback(){

    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
}

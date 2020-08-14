package com.laocuo.hikcamera;

import androidx.appcompat.app.AppCompatActivity;

import android.graphics.ImageFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;

import com.alex.livertmppushsdk.RtmpSessionManager;
import com.alex.livertmppushsdk.SWVideoEncoder;
import com.hikvision.netsdk.HCNetSDK;
import com.hikvision.netsdk.NET_DVR_DEVICEINFO_V30;
import com.hikvision.netsdk.NET_DVR_PREVIEWINFO;
import com.hikvision.netsdk.RealDataCallBack;
import com.hikvision.netsdk.RealPlayCallBack;
import com.hikvision.netsdk.StdDataCallBack;

import org.MediaPlayer.PlayM4.Player;
import org.MediaPlayer.PlayM4.PlayerCallBack;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, View.OnClickListener {

    public static final String TAG = "MAIN";

    private final int WIDTH_DEF = 640;
    private final int HEIGHT_DEF = 360;
    private final int FRAMERATE_DEF = 20;
    private final int BITRATE_DEF = 800 * 1000;

    private final String RTMP_URL = "rtmp://192.168.7.100:1935/live/stream";

    private int _iCameraCodecType = ImageFormat.YV12;

    private Button mTest1, mTest2, mTest3, mTest4;

    private SurfaceView mSurfaceView;

    private SurfaceHolder mSurfaceHolder;

    private NET_DVR_DEVICEINFO_V30 mNETDvrDeviceinfoV30 = null;

    private int mLogId = -1;

    private int mStartChan;

    private int mChanNum;

    private int mPlayId = -1;

    private int mPlayerM4Id = -1;

    private boolean isPushStreaming;

    private RtmpSessionManager _rtmpSessionMgr = null;

    private SWVideoEncoder _swEncH264 = null;

    private byte[] _yuvEdit = new byte[WIDTH_DEF * HEIGHT_DEF * 3 / 2];

    private Queue<byte[]> _YUVQueue = new LinkedList<byte[]>();

    private Lock _yuvQueueLock = new ReentrantLock();

    private Thread publishThread;

    private Runnable publishRunnable = new Runnable() {
        @Override
        public void run() {
            while (!publishThread.isInterrupted() && isPushStreaming) {
                int iSize = _YUVQueue.size();
                if (iSize > 0) {
                    _yuvQueueLock.lock();
                    byte[] yuvData = _YUVQueue.poll();
                    if (iSize > 9) {
                        Log.i(TAG, "###YUV Queue len=" + _YUVQueue.size() + ", YUV length=" + yuvData.length);
                    }
                    _yuvQueueLock.unlock();
                    if (yuvData == null) {
                        continue;
                    }
//                    _yuvEdit = _swEncH264.YUV420pRotate90(yuvData, HEIGHT_DEF, WIDTH_DEF);
                    byte[] h264Data = _swEncH264.EncoderH264(yuvData);
                    if (h264Data != null) {
                        Log.i(TAG, "h264Data.length:" + h264Data.length);
                        _rtmpSessionMgr.InsertVideoData(h264Data);
                    } else {
                        Log.i(TAG, "h264Data == null");
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            _YUVQueue.clear();
        }
    };

    private void processRealData(int id, int type, byte[] data, int user, boolean show) {
        Log.i(TAG, "encodeH264 id=" + id + " type=" + type + " data.length=" + data.length + " user=" + user);
        if (type == HCNetSDK.NET_DVR_SYSHEAD) {
            mPlayerM4Id = Player.getInstance().getPort();
            Log.i(TAG, "getPort mPlayerM4Id=" + mPlayerM4Id);
            if (mPlayerM4Id < 0) {
                return;
            }
            if (user > 0) {
                if (!Player.getInstance().setStreamOpenMode(mPlayerM4Id, Player.STREAM_REALTIME)) {
                    Log.e(TAG, "setStreamOpenMode fail");
                    return;
                } else {
                    Log.i(TAG, "setStreamOpenMode success");
                }
                if (!Player.getInstance().openStream(mPlayerM4Id, data, user, 2 * 1024*1024)) {
                    Log.e(TAG, "openStream fail");
                    return;
                } else {
                    Log.i(TAG, "openStream success");
                }
                if (show) {
                    if (!Player.getInstance().setDecodeCBEx(mPlayerM4Id, mPlayerDecodeCBEx)) {
                        Log.e(TAG, "setDecodeCBEx fail");
                        return;
                    } else {
                        Log.i(TAG, "setDecodeCBEx success");
                    }
                } else {
                    if (!Player.getInstance().setDecodeCB(mPlayerM4Id, mPlayerDecodeCB)) {
                        Log.e(TAG, "setDecodeCB fail");
                        return;
                    } else {
                        Log.i(TAG, "setDecodeCB success");
                    }
                }
                if (!Player.getInstance().setDisplayBuf(mPlayerM4Id, 6)) {
                    Log.e(TAG, "setDisplayBuf fail");
                    return;
                } else {
                    Log.i(TAG, "setDisplayBuf success");
                }
                if (!Player.getInstance().play(mPlayerM4Id, mSurfaceHolder)) {
                    Log.e(TAG, "play fail");
                    return;
                } else {
                    Log.i(TAG, "play success");
                }
            }
        } else if (type == HCNetSDK.NET_DVR_STREAMDATA) {
            if (mPlayerM4Id >= 0 && user > 0) {
                    if (!Player.getInstance().inputData(mPlayerM4Id, data, user)) {
                        Log.e(TAG, "inputData fail");
                        return;
                    }
                }
        }
    }

    private PlayerCallBack.PlayerDecodeCB mPlayerDecodeCB = new PlayerCallBack.PlayerDecodeCB() {
        @Override
        public void onDecode(int i, byte[] bytes, int i1, int i2, int i3, int i4, int i5, int i6) {
            Log.i(TAG, "onDecode i=" + i +
                    " i1=" + i1 + " i2=" + i2 + " i3=" + i3 + " i4=" + i4 + " i5=" + i5 + " i6=" + i6);
            if (isPushStreaming) {
                encodeYUV420(bytes);
            }
        }
    };

    private PlayerCallBack.PlayerDecodeCBEx mPlayerDecodeCBEx = new PlayerCallBack.PlayerDecodeCBEx() {

        @Override
        public void onDecodeEx(int i, byte[] bytes, int i1, int i2, int i3, int i4, int i5, int i6, int i7, int i8, int i9, int i10, int i11, int i12, int i13) {
            Log.i(TAG, "onDecodeEx i=" + i +
                    " i1=" + i1 + " i2=" + i2 + " i3=" + i3 + " i4=" + i4 + " i5=" + i5 +
                    " i6=" + i6 + " i7=" + i7 + " i8=" + i8 + " i9=" + i9 + " i10=" + i10 +
                    " i11=" + i11 + " i12=" + i12 + " i13=" + i13);
            if (isPushStreaming) {
                encodeYUV420(bytes);
            }
        }
    };

    private void encodeYUV420(byte[] data) {
        byte[] yuv420 = null;
        if (_iCameraCodecType == ImageFormat.YV12) {
            yuv420 = new byte[data.length];
            _swEncH264.swapYV12toI420_Ex(data, yuv420, HEIGHT_DEF, WIDTH_DEF);
        } else if (_iCameraCodecType == ImageFormat.NV21) {
            yuv420 = _swEncH264.swapNV21toI420(data, HEIGHT_DEF, WIDTH_DEF);
        }
        if (yuv420 == null) {
            return;
        }
        _yuvQueueLock.lock();
        if (_YUVQueue.size() > 1) {
            _YUVQueue.clear();
        }
        _YUVQueue.offer(yuv420);
        _yuvQueueLock.unlock();
    }

    private RealPlayCallBack mRealPlayCallBack = new RealPlayCallBack() {

        @Override
        public void fRealDataCallBack(int id, int type, byte[] data, int user) {
            processRealData(id, type, data, user, true);
        }
    };

    private RealDataCallBack mRealDataCallBack = new RealDataCallBack() {
        @Override
        public void fRealDataCallBack(int id, int type, byte[] data, int user) {
            processRealData(id, type, data, user, true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mTest1 = findViewById(R.id.test1);
        mTest1.setOnClickListener(this);
        mTest2 = findViewById(R.id.test2);
        mTest2.setOnClickListener(this);
        mTest3 = findViewById(R.id.test3);
        mTest3.setOnClickListener(this);
        mTest4 = findViewById(R.id.test4);
        mTest4.setOnClickListener(this);
        mSurfaceView = findViewById(R.id.sv);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
        initCamera();
        _rtmpSessionMgr = new RtmpSessionManager();
        _swEncH264 = new SWVideoEncoder(WIDTH_DEF, HEIGHT_DEF, FRAMERATE_DEF, BITRATE_DEF);
        _swEncH264.start(_iCameraCodecType);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isPushStreaming) {
            stopPushStream();
        }
        if (mPlayId >= 0) {
            HCNetSDK.getInstance().NET_DVR_StopRealPlay(mPlayId);
        }
        if (mLogId >= 0) {
            HCNetSDK.getInstance().NET_DVR_Logout_V30(mLogId);
        }
        HCNetSDK.getInstance().NET_DVR_Cleanup();
        _swEncH264.stop();
    }

    private void initCamera() {
        boolean ret = HCNetSDK.getInstance().NET_DVR_Init();
        if (ret) {
            Log.d(TAG, "NET_DVR_Init success");
        } else {
            Log.d(TAG, "NET_DVR_Init fail");
        }
        mNETDvrDeviceinfoV30 = new NET_DVR_DEVICEINFO_V30();
        mLogId = HCNetSDK.getInstance().NET_DVR_Login_V30("192.168.7.64",
                8000,
                "admin",
                "admin123",
                mNETDvrDeviceinfoV30);
        if (mLogId < 0) {
            Log.d(TAG, "NET_DVR_Login_V30 fail mLogId=" + mLogId);
            Log.e(TAG, "NET_DVR_Login_V30 is failed!Err:" + HCNetSDK.getInstance().NET_DVR_GetLastError());
            return;
        } else {
            mStartChan = mNETDvrDeviceinfoV30.byStartChan;
            mChanNum = mNETDvrDeviceinfoV30.byChanNum;
            Log.d(TAG, "NET_DVR_Login_V30 success mLogId=" + mLogId + " mStartChan=" + mStartChan + " mChanNum=" + mChanNum);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int i, int i1, int i2) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.test1:
                if (mLogId >= 0) {
                    startPreview();
                }
                break;
            case R.id.test2:
                if (mPlayId >= 0) {
                    stopPreview();
                }
                break;
            case R.id.test3:
                if (mPlayId >= 0) {
                    startPushStream();
                }
                break;
            case R.id.test4:
                if (mPlayId >= 0) {
                    stopPushStream();
                }
                break;
        }
    }

    private void startPreview() {
        NET_DVR_PREVIEWINFO previewInfo = new NET_DVR_PREVIEWINFO();
        previewInfo.lChannel = mStartChan; //预览通道号
        previewInfo.dwStreamType = 1; //0-主流码，1-子流码，2-流码3，3-流码4，以此类推
        previewInfo.bBlocked = 1; //0-非堵塞取流，1-堵塞取流
        previewInfo.dwLinkMode = 0; //0-TCP方式，1-UDP方式，2-多播方式，3-RTP方式，4-RTP/RTSP，5-RSTP/HTTP
        previewInfo.hHwnd = null; //窗口为空，设备SDK不解码只取流
        mPlayId = HCNetSDK.getInstance().NET_DVR_RealPlay_V40(mLogId, previewInfo, mRealPlayCallBack);
        if (mPlayId < 0) {
            Log.d(TAG, "NET_DVR_RealPlay_V40 fail mPlayId=" + mPlayId);
            return;
        } else {
            Log.d(TAG, "NET_DVR_RealPlay_V40 success mPlayId=" + mPlayId);
        }
    }

    private void stopPreview() {
        if (isPushStreaming) {
            stopPushStream();
        }
        boolean ret = HCNetSDK.getInstance().NET_DVR_StopRealPlay(mPlayId);
        if (ret) {
            Log.d(TAG, "NET_DVR_StopRealPlay success");
        } else {
            Log.d(TAG, "NET_DVR_StopRealPlay fail");
        }
        mPlayId = -1;
    }

    private void startPushStream() {
        _rtmpSessionMgr.Start(RTMP_URL);
//        setRealDataCallBack();
        isPushStreaming = true;
        publishThread = new Thread(publishRunnable);
        publishThread.start();
    }

    private void stopPushStream() {
        publishThread.interrupt();;
        isPushStreaming = false;
//        unSetRealDataCallBack();
        _rtmpSessionMgr.Stop();
    }

    private void setRealDataCallBack() {
        boolean ret = HCNetSDK.getInstance().NET_DVR_SetRealDataCallBack(mPlayId, mRealDataCallBack);
        if (ret) {
            Log.d(TAG, "NET_DVR_SetRealDataCallBack this success");
        } else {
            Log.d(TAG, "NET_DVR_SetRealDataCallBack this fail");
        }
    }

    private void unSetRealDataCallBack() {
        boolean ret = HCNetSDK.getInstance().NET_DVR_SetRealDataCallBack(mPlayId, null);
        if (ret) {
            Log.d(TAG, "NET_DVR_SetRealDataCallBack null success");
        } else {
            Log.d(TAG, "NET_DVR_SetRealDataCallBack null fail");
        }
    }
}

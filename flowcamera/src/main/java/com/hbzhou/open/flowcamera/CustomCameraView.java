package com.hbzhou.open.flowcamera;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.bumptech.glide.Glide;
import com.hbzhou.open.flowcamera.listener.ClickListener;
import com.hbzhou.open.flowcamera.listener.FlowCameraListener;
import com.hbzhou.open.flowcamera.listener.OnVideoPlayPrepareListener;
import com.hbzhou.open.flowcamera.listener.TypeListener;
import com.hbzhou.open.flowcamera.util.LogUtil;
import com.otaliastudios.cameraview.CameraException;
import com.otaliastudios.cameraview.CameraListener;
import com.otaliastudios.cameraview.CameraView;
import com.otaliastudios.cameraview.PictureResult;
import com.otaliastudios.cameraview.VideoResult;
import com.otaliastudios.cameraview.controls.Audio;
import com.otaliastudios.cameraview.controls.Engine;
import com.otaliastudios.cameraview.controls.Flash;
import com.otaliastudios.cameraview.controls.Hdr;
import com.otaliastudios.cameraview.controls.Mode;
import com.otaliastudios.cameraview.controls.WhiteBalance;
import com.otaliastudios.cameraview.filter.Filter;
import com.otaliastudios.cameraview.filters.FillLightFilter;

import java.io.File;
import java.io.IOException;
import java.util.Objects;


/**
 * author hbzhou
 * date 2019/12/27 13:30
 * 新增一个CustomCameraView 暂时兼容性较好
 */
public class CustomCameraView extends FrameLayout {

    private Context mContext;
    private CameraView mCameraView;
    private ImageView mPhoto;
    private ImageView mSwitchCamera;
    private ImageView mFlashLamp;
    private CaptureLayout mCaptureLayout;
    private MediaPlayer mMediaPlayer;
    private TextureView mTextureView;

    //闪关灯状态
    private static final int TYPE_FLASH_AUTO = 0x021;
    private static final int TYPE_FLASH_ON = 0x022;
    private static final int TYPE_FLASH_OFF = 0x023;
    private int type_flash = TYPE_FLASH_OFF;
    //回调监听
    private FlowCameraListener flowCameraListener;
    private ClickListener leftClickListener;

    private File videoFile;
    private File photoFile;
    //切换摄像头按钮的参数
    private int iconSrc;        //图标资源
    private int iconLeft;       //左图标
    private int iconRight;      //右图标
    private int duration;      //录制时间
    private long recordTime = 0;

    public CustomCameraView(@NonNull Context context) {
        this(context, null);
    }

    public CustomCameraView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomCameraView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;
        TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.CustomCameraView, defStyleAttr, 0);
        iconSrc = a.getResourceId(R.styleable.CustomCameraView_iconSrc, R.drawable.ic_camera);
        iconLeft = a.getResourceId(R.styleable.CustomCameraView_iconLeft, 0);
        iconRight = a.getResourceId(R.styleable.CustomCameraView_iconRight, 0);
        duration = a.getInteger(R.styleable.CustomCameraView_duration_max, 10 * 1000);       //没设置默认为10s
        a.recycle();
        initView();
    }

    public void initView() {
        setWillNotDraw(false);
        View view = LayoutInflater.from(mContext).inflate(R.layout.custom_camera_view, this);
        mCameraView = view.findViewById(R.id.video_preview);
        mTextureView = view.findViewById(R.id.mVideo);
        mPhoto = view.findViewById(R.id.image_photo);
        mSwitchCamera = view.findViewById(R.id.image_switch);
        mSwitchCamera.setImageResource(iconSrc);
        mFlashLamp = view.findViewById(R.id.image_flash);
        setFlashRes();
        mFlashLamp.setOnClickListener(v -> {
            type_flash++;
            if (type_flash > 0x023)
                type_flash = TYPE_FLASH_AUTO;
            setFlashRes();
        });
        mCaptureLayout = view.findViewById(R.id.capture_layout);
        mCaptureLayout.setDuration(duration);
        mCaptureLayout.setIconSrc(iconLeft, iconRight);
        //切换摄像头
        mSwitchCamera.setOnClickListener(v ->
                mCameraView.toggleFacing()
        );
        mCameraView.setHdr(Hdr.ON);
        mCameraView.setAudio(Audio.ON);
        // 拍照录像回调
        mCameraView.addCameraListener(new CameraListener() {
            @Override
            public void onCameraError(@NonNull CameraException exception) {
                super.onCameraError(exception);
                if (flowCameraListener != null) {
                    flowCameraListener.onError(0, Objects.requireNonNull(exception.getMessage()), null);
                }
                //LogUtil.e("CameraException---" + exception.getMessage());
            }

            @Override
            public void onPictureTaken(@NonNull PictureResult result) {
                super.onPictureTaken(result);
                result.toFile(initTakePicPath(mContext), file -> {
                    if (file == null || !file.exists()) {
                        return;
                    }
                    photoFile = file;
                    Glide.with(mContext)
                            .load(file)
                            .into(mPhoto);
                    mPhoto.setVisibility(View.VISIBLE);
                    mCaptureLayout.startTypeBtnAnimator();

                    // If the folder selected is an external media directory, this is unnecessary
                    // but otherwise other apps will not be able to access our images unless we
                    // scan them using [MediaScannerConnection]
                    String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.getAbsolutePath().substring(file.getAbsolutePath().lastIndexOf(".") + 1));
                    MediaScannerConnection.scanFile(
                            mContext, new String[]{file.getAbsolutePath()}, new String[]{mimeType}, null);
                });
            }

            @Override
            public void onVideoTaken(@NonNull VideoResult result) {
                super.onVideoTaken(result);
                videoFile = result.getFile();
                if (!videoFile.exists() || (recordTime < 1500 && videoFile.exists() && videoFile.delete())) {
                    return;
                }
                mCaptureLayout.startTypeBtnAnimator();
                mTextureView.setVisibility(View.VISIBLE);
                if (mTextureView.isAvailable()) {
                    startVideoPlay(videoFile, () ->
                            mCameraView.setVisibility(View.GONE)
                    );
                } else {
                    mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                        @Override
                        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                            startVideoPlay(videoFile, () ->
                                    mCameraView.setVisibility(View.GONE)
                            );
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
                    });
                }

                // If the folder selected is an external media directory, this is unnecessary
                // but otherwise other apps will not be able to access our images unless we
                // scan them using [MediaScannerConnection]
                String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(videoFile.getAbsolutePath().substring(videoFile.getAbsolutePath().lastIndexOf(".") + 1));
                MediaScannerConnection.scanFile(
                        mContext, new String[]{videoFile.getAbsolutePath()}, new String[]{mimeType}, null);
            }
        });
        mCameraView.setEngine(Engine.CAMERA2);
        //拍照 录像
        mCaptureLayout.setCaptureLisenter(new CaptureListener() {
            @Override
            public void takePictures() {
                mSwitchCamera.setVisibility(INVISIBLE);
                mFlashLamp.setVisibility(INVISIBLE);
                mCameraView.setMode(Mode.PICTURE);
                mCameraView.takePictureSnapshot();
            }

            @Override
            public void recordStart() {
                mSwitchCamera.setVisibility(INVISIBLE);
                mFlashLamp.setVisibility(INVISIBLE);
                mCameraView.setMode(Mode.VIDEO);
                if (mCameraView.isTakingVideo()) {
                    mCameraView.stopVideo();
                } else {
                    mCameraView.takeVideoSnapshot(initStartRecordingPath(mContext));
                }
            }

            @Override
            public void recordShort(final long time) {
                recordTime = time;
                mSwitchCamera.setVisibility(VISIBLE);
                mFlashLamp.setVisibility(VISIBLE);
                mCaptureLayout.resetCaptureLayout();
                mCaptureLayout.setTextWithAnimation("录制时间过短");
                if (mCameraView.isTakingVideo()) {
                    mCameraView.stopVideo();
                }
            }

            @Override
            public void recordEnd(long time) {
                recordTime = time;
                if (mCameraView.isTakingVideo()) {
                    mCameraView.stopVideo();
                }
            }

            @Override
            public void recordZoom(float zoom) {

            }

            @Override
            public void recordError() {
                if (flowCameraListener != null) {
                    flowCameraListener.onError(0, "未知原因!", null);
                }
            }
        });
        //确认 取消
        mCaptureLayout.setTypeLisenter(new TypeListener() {
            @Override
            public void cancel() {
                stopVideoPlay();
                resetState();
            }

            @Override
            public void confirm() {
                if (mCameraView.getMode() == Mode.VIDEO) {
                    stopVideoPlay();
                    if (flowCameraListener != null) {
                        flowCameraListener.recordSuccess(videoFile);
                    }
                } else {
                    mPhoto.setVisibility(INVISIBLE);
                    if (flowCameraListener != null) {
                        flowCameraListener.captureSuccess(photoFile);
                    }
                }
            }
        });
        mCaptureLayout.setLeftClickListener(() -> {
            if (leftClickListener != null) {
                leftClickListener.onClick();
            }
        });
    }

    public File initTakePicPath(Context context) {
        return new File(context.getExternalMediaDirs()[0], System.currentTimeMillis() + ".jpg");
    }

    public File initStartRecordingPath(Context context) {
        return new File(context.getExternalMediaDirs()[0], System.currentTimeMillis() + ".mp4");
    }

    /**************************************************
     * 对外提供的API                     *
     **************************************************/

    public void setFlowCameraListener(FlowCameraListener flowCameraListener) {
        this.flowCameraListener = flowCameraListener;
    }

    // 绑定生命周期 否者界面可能一片黑
    public void setBindToLifecycle(LifecycleOwner lifecycleOwner) {
        mCameraView.setLifecycleOwner(lifecycleOwner);
        lifecycleOwner.getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            LogUtil.i("event---", event.toString());
            if (event == Lifecycle.Event.ON_RESUME) {
                mCameraView.open();
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                mCameraView.close();
            } else if (event == Lifecycle.Event.ON_DESTROY) {
                mCameraView.destroy();
            }
        });
    }

    /**
     * 设置录制视频最大时长单位 s
     */
    public void setRecordVideoMaxTime(int maxDurationTime) {
        mCaptureLayout.setDuration(maxDurationTime * 1000);
    }

    /**
     * 设置是否支持HDR
     * @param hdr
     */
    public void setHdrEnable(Hdr hdr) {
        mCameraView.setHdr(hdr);
    }

    /**
     * 设置白平衡
     *
     * @param whiteBalance
     */
    public void setWhiteBalance(WhiteBalance whiteBalance) {
        mCameraView.setWhiteBalance(whiteBalance);
    }
    
    /**
     * 关闭相机界面按钮
     *
     * @param clickListener
     */
    public void setLeftClickListener(ClickListener clickListener) {
        this.leftClickListener = clickListener;
    }

    private void setFlashRes() {
        switch (type_flash) {
            case TYPE_FLASH_AUTO:
                mFlashLamp.setImageResource(R.drawable.ic_flash_auto);
                mCameraView.setFlash(Flash.AUTO);
                break;
            case TYPE_FLASH_ON:
                mFlashLamp.setImageResource(R.drawable.ic_flash_on);
                mCameraView.setFlash(Flash.ON);
                break;
            case TYPE_FLASH_OFF:
                mFlashLamp.setImageResource(R.drawable.ic_flash_off);
                mCameraView.setFlash(Flash.OFF);
                break;
        }
    }

    /**
     * 重置状态
     */
    private void resetState() {
        if (mCameraView.getMode() == Mode.VIDEO) {
            if (mCameraView.isTakingVideo()) {
                mCameraView.stopVideo();
            }
            if (videoFile != null && videoFile.exists() && videoFile.delete()) {
                LogUtil.i("videoFile is clear");
            }
        } else {
            mPhoto.setVisibility(INVISIBLE);
            if (photoFile != null && photoFile.exists() && photoFile.delete()) {
                LogUtil.i("photoFile is clear");
            }
        }
        mSwitchCamera.setVisibility(VISIBLE);
        mFlashLamp.setVisibility(VISIBLE);
        mCameraView.setVisibility(View.VISIBLE);
        mCaptureLayout.resetCaptureLayout();
    }

    /**
     * 开始循环播放视频
     *
     * @param videoFile
     */
    private void startVideoPlay(File videoFile, OnVideoPlayPrepareListener
            onVideoPlayPrepareListener) {
        try {
            if (mMediaPlayer == null) {
                mMediaPlayer = new MediaPlayer();
            }
            mMediaPlayer.setDataSource(videoFile.getAbsolutePath());
            mMediaPlayer.setSurface(new Surface(mTextureView.getSurfaceTexture()));
            mMediaPlayer.setLooping(true);
            mMediaPlayer.setOnPreparedListener(mp -> {
                mp.start();

                float ratio = mp.getVideoWidth() * 1f / mp.getVideoHeight();
                int width1 = mTextureView.getWidth();
                ViewGroup.LayoutParams layoutParams = mTextureView.getLayoutParams();
                layoutParams.height = (int) (width1 / ratio);
                mTextureView.setLayoutParams(layoutParams);

                if (onVideoPlayPrepareListener != null) {
                    onVideoPlayPrepareListener.onPrepared();
                }
            });
            mMediaPlayer.prepareAsync();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 停止视频播放
     */
    private void stopVideoPlay() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        mTextureView.setVisibility(View.GONE);
    }
}

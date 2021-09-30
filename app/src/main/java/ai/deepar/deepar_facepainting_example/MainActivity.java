package ai.deepar.deepar_facepainting_example;

import ai.deepar.ar.ARErrorType;
import ai.deepar.ar.AREventListener;
import ai.deepar.ar.ARTouchInfo;
import ai.deepar.ar.ARTouchType;
import ai.deepar.ar.CameraResolutionPreset;
import ai.deepar.ar.DeepAR;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.media.Image;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutionException;

import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.LifecycleOwner;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, AREventListener {

    // Default camera lens value, change to CameraSelector.LENS_FACING_BACK to initialize with back camera
    private final int defaultLensFacing = CameraSelector.LENS_FACING_FRONT;
    private ARSurfaceProvider surfaceProvider = null;
    private int lensFacing = defaultLensFacing;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    private DeepAR deepAR;

    private boolean recording = false;
    private boolean currentSwitchRecording = false;

    private int width = 0;
    private int height = 0;

    private File videoFileName;

    private LinearLayout buttonCollection;
    private SeekBar brushSizeBar;
    private float[] color;
    private float scale;
    private static final float minValue = 0.005f; //50/10000
    private static final float maxValue = 0.25f;  //2500/10000

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this, new String[]{ Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO },
                    1);
        } else {
            // Permission has already been granted
            initialize();
        }
        super.onStart();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == 1 && grantResults.length > 0) {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    return; // no permission
                }
            }
            initialize();
        }
    }

    private void initialize() {
        initializeDeepAR();
        initalizeViews();
    }

    private void initalizeViews() {
        color = new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        scale = 0.03f;

        buttonCollection = findViewById(R.id.buttonCollection);
        brushSizeBar = findViewById(R.id.brushSizeBar);
        brushSizeBar.setProgress(1225);
        brushSizeBar.getThumb().setTint(Color.BLACK);

        SurfaceView arView = findViewById(R.id.surface);

        arView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    deepAR.touchOccurred(new ARTouchInfo(event.getX(), event.getY(), ARTouchType.Start));
                    return true;
                case MotionEvent.ACTION_MOVE:
                    deepAR.touchOccurred(new ARTouchInfo(event.getX(), event.getY(), ARTouchType.Move));
                    return true;
                case MotionEvent.ACTION_UP:
                    deepAR.touchOccurred(new ARTouchInfo(event.getX(), event.getY(), ARTouchType.End));
                    return true;
            }

            return false;
        });

        arView.getHolder().addCallback(this);

        // Surface might already be initialized, so we force the call to onSurfaceChanged
        arView.setVisibility(View.GONE);
        arView.setVisibility(View.VISIBLE);

        final ImageButton screenshotBtn = findViewById(R.id.recordButton);
        screenshotBtn.setOnClickListener(v -> deepAR.takeScreenshot());

        ImageButton switchCamera = findViewById(R.id.switchCamera);
        switchCamera.setOnClickListener(v -> {
            lensFacing = lensFacing ==  CameraSelector.LENS_FACING_FRONT ?  CameraSelector.LENS_FACING_BACK :  CameraSelector.LENS_FACING_FRONT ;
            //unbind immediately to avoid mirrored frame.
            ProcessCameraProvider cameraProvider;
            try {
                cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
            setupCamera();
        });

        final TextView screenShotModeButton = findViewById(R.id.screenshotModeButton);
        final TextView recordModeBtn = findViewById(R.id.recordModeButton);

        recordModeBtn.getBackground().setAlpha(0x00);
        screenShotModeButton.getBackground().setAlpha(0xA0);

        screenShotModeButton.setOnClickListener(v -> {
            if(currentSwitchRecording) {
                if(recording) {
                    Toast.makeText(getApplicationContext(), "Cannot switch to screenshots while recording!", Toast.LENGTH_SHORT).show();
                    return;
                }

                recordModeBtn.getBackground().setAlpha(0x00);
                screenShotModeButton.getBackground().setAlpha(0xA0);
                screenshotBtn.setOnClickListener(v1 -> deepAR.takeScreenshot());

                currentSwitchRecording = !currentSwitchRecording;
            }
        });



        recordModeBtn.setOnClickListener(v -> {

            if(!currentSwitchRecording) {

                recordModeBtn.getBackground().setAlpha(0xA0);
                screenShotModeButton.getBackground().setAlpha(0x00);
                screenshotBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if(recording) {
                            deepAR.stopVideoRecording();
                            Toast.makeText(getApplicationContext(), "Recording " + videoFileName.getName() + " saved.", Toast.LENGTH_LONG).show();
                        } else {
                            videoFileName = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "video_" + new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date()) + ".mp4");
                            deepAR.startVideoRecording(videoFileName.toString(), width/2, height/2);
                            Toast.makeText(getApplicationContext(), "Recording started.", Toast.LENGTH_SHORT).show();
                        }
                        recording = !recording;
                    }
                });

                currentSwitchRecording = !currentSwitchRecording;
            }
        });

        ImageButton whiteBrush = findViewById(R.id.whiteBrush);
        whiteBrush.setOnClickListener(v -> {
            color = new float[]{1.0f, 1.0f, 1.0f, 1.0f};

            deepAR.changeParameterVec4("PaintBrush", "MeshRenderer", "u_color", color[0], color[1], color[2], color[3]);
            brushSizeBar.getThumb().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        });

        ImageButton greyBrush = findViewById(R.id.greyBrush);
        greyBrush.setOnClickListener(v -> {
            color = new float[]{0.5f, 0.5f, 0.5f, 1.0f};

            deepAR.changeParameterVec4("PaintBrush", "MeshRenderer", "u_color", color[0], color[1], color[2], color[3]);
            brushSizeBar.getThumb().setColorFilter(Color.GRAY, PorterDuff.Mode.SRC_IN);
        });

        ImageButton blackBrush = findViewById(R.id.blackBrush);
        blackBrush.setOnClickListener(v -> {
            color = new float[]{0.0f, 0.0f, 0.0f, 1.0f};

            deepAR.changeParameterVec4("PaintBrush", "MeshRenderer", "u_color", color[0], color[1], color[2], color[3]);
            brushSizeBar.getThumb().setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);
        });

        ImageButton orangeBrush = findViewById(R.id.orangeBrush);
        orangeBrush.setOnClickListener(v -> {
            color = new float[]{209.0f / 255, 82.0f / 255, 23.0f / 255, 1.0f};

            deepAR.changeParameterVec4("PaintBrush", "MeshRenderer", "u_color", color[0], color[1], color[2], color[3]);
            brushSizeBar.getThumb().setColorFilter(Color.argb((int) (color[3] * 255),(int) (color[0] * 255),(int) (color[1] * 255),(int) (color[2] * 255)), PorterDuff.Mode.SRC_IN);
        });

        ImageButton greenBrush = findViewById(R.id.greenBrush);
        greenBrush.setOnClickListener(v -> {
            color = new float[]{132.0f / 255, 184.0f / 255, 95.0f / 255, 1.0f};

            deepAR.changeParameterVec4("PaintBrush", "MeshRenderer", "u_color", color[0], color[1], color[2], color[3]);
            brushSizeBar.getThumb().setColorFilter(Color.argb((int) (color[3] * 255),(int) (color[0] * 255),(int) (color[1] * 255),(int) (color[2] * 255)), PorterDuff.Mode.SRC_IN);
        });

        ImageButton blueBrush = findViewById(R.id.blueBrush);
        blueBrush.setOnClickListener(v -> {
            color = new float[]{95.0f / 255, 160.0f / 255, 184.0f / 255, 1.0f};

            deepAR.changeParameterVec4("PaintBrush", "MeshRenderer", "u_color", color[0], color[1], color[2], color[3]);
            brushSizeBar.getThumb().setColorFilter(Color.argb((int) (color[3] * 255),(int) (color[0] * 255),(int) (color[1] * 255),(int) (color[2] * 255)), PorterDuff.Mode.SRC_IN);
        });

        ImageButton eraser = findViewById(R.id.eraser);
        eraser.setOnClickListener(v -> {
            color = new float[]{0.0f, 0.0f, 0.0f, 0.0f};

            deepAR.changeParameterVec4("PaintBrush", "MeshRenderer", "u_color", color[0], color[1], color[2], color[3]);
            brushSizeBar.getThumb().setColorFilter(Color.argb((int) (0.1 * 255), (int) (0.5 * 255), (int) (0.5 * 255), (int) (0.5 * 255)), PorterDuff.Mode.SRC_IN);
        });

        ImageButton clear = findViewById(R.id.clearPainting);
        clear.setOnClickListener(v -> {
            float oldScale = scale;
            float[] oldColor = {color[0], color[1], color[2], color[3]};

            deepAR.switchEffect("mask", getFilterPath("facePainting"));
            deepAR.changeParameterVec4("PaintBrush", "MeshRenderer", "u_color", oldColor[0], oldColor[1], oldColor[2], oldColor[3]);
            deepAR.changeParameterVec3("PaintBrush", "", "scale", oldScale, oldScale, oldScale);
        });

        brushSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = progress / 10000.0f;
                scale = (1 - value) * minValue + value * maxValue;
                deepAR.changeParameterVec3("PaintBrush", "", "scale", scale, scale, scale);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }
    /*
            get interface orientation from
            https://stackoverflow.com/questions/10380989/how-do-i-get-the-current-orientation-activityinfo-screen-orientation-of-an-a/10383164
         */
    private int getScreenOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
        int orientation;
        // if the device's natural orientation is portrait:
        if ((rotation == Surface.ROTATION_0
                || rotation == Surface.ROTATION_180) && height > width ||
                (rotation == Surface.ROTATION_90
                        || rotation == Surface.ROTATION_270) && width > height) {
            switch(rotation) {
                case Surface.ROTATION_0:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    break;
                case Surface.ROTATION_90:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    break;
                case Surface.ROTATION_180:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                    break;
                case Surface.ROTATION_270:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                    break;
                default:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    break;
            }
        }
        // if the device's natural orientation is landscape or if the device
        // is square:
        else {
            switch(rotation) {
                case Surface.ROTATION_0:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    break;
                case Surface.ROTATION_90:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    break;
                case Surface.ROTATION_180:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                    break;
                case Surface.ROTATION_270:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                    break;
                default:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    break;
            }
        }

        return orientation;
    }
    private void initializeDeepAR() {
        deepAR = new DeepAR(this);
        deepAR.setLicenseKey("your-license-key-here");
        deepAR.initialize(this, this);
        setupCamera();
    }

    private void setupCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    bindImageAnalysis(cameraProvider);
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindImageAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        CameraResolutionPreset cameraResolutionPreset = CameraResolutionPreset.P1920x1080;
        int width;
        int height;
        int orientation = getScreenOrientation();

        RelativeLayout.LayoutParams openActivityParams = new RelativeLayout.LayoutParams(
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50, getResources().getDisplayMetrics()),
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50, getResources().getDisplayMetrics())
        );

        RelativeLayout.LayoutParams buttonCollectionParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
        );

        RelativeLayout.LayoutParams brushSizeBarParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics())
        );

        if (orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE || orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            width = cameraResolutionPreset.getWidth();
            height =  cameraResolutionPreset.getHeight();

            openActivityParams.setMargins(
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics()),
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 45, getResources().getDisplayMetrics()),
                    0, 0);

            buttonCollection.setOrientation(LinearLayout.HORIZONTAL);
            buttonCollectionParams.setMargins(
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 65, getResources().getDisplayMetrics()),
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50, getResources().getDisplayMetrics()),
                    0, 0);

            brushSizeBarParams.setMargins(
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 55, getResources().getDisplayMetrics()),
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15, getResources().getDisplayMetrics()),
                    0, 0);
        } else {
            width = cameraResolutionPreset.getHeight();
            height = cameraResolutionPreset.getWidth();

            openActivityParams.addRule(RelativeLayout.ALIGN_PARENT_END, RelativeLayout.TRUE);
            openActivityParams.setMargins(
                    0,
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15, getResources().getDisplayMetrics()),
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5, getResources().getDisplayMetrics()),
                    0
            );

            buttonCollection.setOrientation(LinearLayout.VERTICAL);
            buttonCollectionParams.setMargins(
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, getResources().getDisplayMetrics()),
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 85, getResources().getDisplayMetrics()),
                    0, 0);

            brushSizeBarParams.setMargins(
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 55, getResources().getDisplayMetrics()),
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 55, getResources().getDisplayMetrics()),
                    0, 0);
        }
        buttonCollection.setLayoutParams(buttonCollectionParams);
        brushSizeBar.setLayoutParams(brushSizeBarParams);
        brushSizeBar.setProgress(1225);
        brushSizeBar.getThumb().setTint(Color.BLACK);

        Size cameraResolution = new Size(width, height);
        CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

        Preview preview = new Preview.Builder()
                .setTargetResolution(cameraResolution)
                .build();

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle((LifecycleOwner)this, cameraSelector, preview);
        if(surfaceProvider == null) {
            surfaceProvider = new ARSurfaceProvider(this, deepAR);
        }
        preview.setSurfaceProvider(surfaceProvider);
        surfaceProvider.setMirror(lensFacing == CameraSelector.LENS_FACING_FRONT);

    }

    private String getFilterPath(String filterName) {
        if (filterName.equals("none")) {
            return null;
        }
        return "file:///android_asset/" + filterName;
    }

    @Override
    protected void onStop() {
        recording = false;
        currentSwitchRecording = false;
        if(surfaceProvider != null) {
            surfaceProvider.stop();
            surfaceProvider = null;
        }
        deepAR.release();
        deepAR = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(surfaceProvider != null) {
            surfaceProvider.stop();
        }
        if (deepAR == null) {
            return;
        }
        deepAR.setAREventListener(null);
        deepAR.release();
        deepAR = null;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // If we are using on screen rendering we have to set surface view where DeepAR will render
        deepAR.setRenderSurface(holder.getSurface(), width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (deepAR != null) {
            deepAR.setRenderSurface(null, 0, 0);
        }
    }

    @Override
    public void screenshotTaken(Bitmap bitmap) {
        CharSequence now = DateFormat.format("yyyy_MM_dd_hh_mm_ss", new Date());
        try {
            File imageFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "image_" + now + ".jpg");
            FileOutputStream outputStream = new FileOutputStream(imageFile);
            int quality = 100;
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
            outputStream.flush();
            outputStream.close();
            MediaScannerConnection.scanFile(MainActivity.this, new String[]{imageFile.toString()}, null, null);
            Toast.makeText(MainActivity.this, "Screenshot " + imageFile.getName() + " saved.", Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            e.printStackTrace();
        }

    }

    @Override
    public void videoRecordingStarted() {

    }

    @Override
    public void videoRecordingFinished() {

    }

    @Override
    public void videoRecordingFailed() {

    }

    @Override
    public void videoRecordingPrepared() {

    }

    @Override
    public void shutdownFinished() {

    }

    @Override
    public void initialized() {
        // Restore effect state after deepar release
        deepAR.switchEffect("mask", getFilterPath("facePainting"));
    }

    @Override
    public void faceVisibilityChanged(boolean b) {

    }

    @Override
    public void imageVisibilityChanged(String s, boolean b) {

    }

    @Override
    public void frameAvailable(Image image) {

    }

    @Override
    public void error(ARErrorType arErrorType, String s) {

    }


    @Override
    public void effectSwitched(String s) {

    }
}

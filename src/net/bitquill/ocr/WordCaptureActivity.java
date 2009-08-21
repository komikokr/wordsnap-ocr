package net.bitquill.ocr;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Bitmap.CompressFormat;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.FileOutputStream;
import java.io.IOException;

import net.bitquill.ocr.image.GrayImage;
import net.bitquill.ocr.image.SimpleStructuringElement;
import net.bitquill.ocr.weocr.WeOCRClient;

public class WordCaptureActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = WordCaptureActivity.class.getSimpleName();
    
    private static final int MENU_SETTINGS_ID = Menu.FIRST;
    
    private static int dumpCount = 1;  // FIXME temporary
    
    private SurfaceView mPreview;
    private boolean mHasSurface;
    private boolean mAutoFocusInProgress;
    private boolean mPreviewCaptureInProgress;
    private int mPreviewWidth, mPreviewHeight;
    private Camera mCamera;
    private boolean mCameraPreviewing;
    
    private TextView mStatusText;
    private TextView mResultText;
   
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);      
    
        setContentView(R.layout.capture);
        mPreview = (SurfaceView)findViewById(R.id.capture_surface);
        
        mStatusText = (TextView)findViewById(R.id.status_text);
        mResultText = (TextView)findViewById(R.id.result_text);
        mResultText.setVisibility(View.INVISIBLE);
    }
    
    private void startCamera () {
        SurfaceHolder holder = mPreview.getHolder();
        if (mHasSurface) {
            Log.d(TAG, "startCamera after pause");
            // Resumed after pause, surface already exists
            surfaceCreated(holder);
            startCameraPreview();
        } else {
            Log.d(TAG, "startCamera from scratch");
            // Install a SurfaceHolder.Callback so we get notified when the
            // underlying surface is created and destroyed.
            holder.addCallback(this);
            holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }
    }
    
    private void startCameraPreview () {
        if (!mCameraPreviewing) {
            mCamera.startPreview();
            mCameraPreviewing = true;
            requestAutoFocus();  // Do one autofocus
        }
    }
    
    private void stopCameraPreview () {
        if (mCameraPreviewing) {
            mCamera.stopPreview();
            mCameraPreviewing = false;
        }
    }
    
    private void stopCamera () {
        if (mCamera != null) {
            stopCameraPreview();
            mCamera.release();
            mCamera = null;
        }
    }
    
    private void requestAutoFocus () {
        if (mAutoFocusInProgress) {
            return;
        }
        mAutoFocusInProgress = true;
        mCamera.autoFocus(new Camera.AutoFocusCallback() { 
            @Override
            public void onAutoFocus(boolean success, Camera camera) {
                Message msg = mHandler.obtainMessage(R.id.msg_auto_focus, 
                        success ? 1 : 0, -1);
                mHandler.sendMessage(msg);
            }
        });
    }
    
    private void requestPreviewFrame () {
        if (mPreviewCaptureInProgress) {
            return;
        }
        mPreviewCaptureInProgress = true;
        mCamera.setOneShotPreviewCallback(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                Message msg = mHandler.obtainMessage(R.id.msg_preview_frame, data);
                mHandler.sendMessage(msg);
            }
        });
    }
    
    @Override
    protected void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        startCamera();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
        stopCamera();
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
      super.onCreateOptionsMenu(menu);
      menu.add(0, MENU_SETTINGS_ID, 0, R.string.menu_settings)
          .setIcon(android.R.drawable.ic_menu_preferences);
      return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_FOCUS) {
            if (event.getRepeatCount() == 0) {
                requestAutoFocus();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event.getRepeatCount() == 0) {
                requestPreviewFrame();
            }
            return true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_FOCUS) {
            requestAutoFocus();
            return true;
        } else {
            return super.onKeyUp(keyCode, event);
        }
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case MENU_SETTINGS_ID:
            startActivity(new Intent(this, OCRPreferences.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        // The Surface has been created, acquire the camera and tell it where
        // to draw.
        mCamera = Camera.open();
        try {
           mCamera.setPreviewDisplay(holder);
           Log.d(TAG, "surfaceCreated: setPreviewDisplay");
        } catch (IOException e) {
            Log.e(TAG, "Camera preview failed", e);
            mCamera.release();
            mCamera = null;
            // TODO: add more exception handling logic here
        }
        mHasSurface = true;
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface will be destroyed when we return, so stop the preview.
        // Because the CameraDevice object is not a shared resource, it's very
        // important to release it when the activity is paused.
        stopCamera();
        mHasSurface = false;
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        // Size is known: set up camera parameters for preview size
        Camera.Parameters params = mCamera.getParameters();
        params.setPreviewSize(w, h);
        mCamera.setParameters(params);
        
        // Get parameters back; actual preview size may differ
        params = mCamera.getParameters();
        Size sz = params.getPreviewSize();
        mPreviewWidth = sz.width;
        mPreviewHeight = sz.height;

        startCameraPreview();
        
        Log.d(TAG, "surfaceChanged: startPreview");
    }
    
    private final Handler mHandler = new Handler () {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case R.id.msg_auto_focus:
                //boolean autoFocusSuccess = (msg.arg1 != 0) ? true : false;
                mAutoFocusInProgress = false;
                break;
            case R.id.msg_preview_frame:
                //mPreviewCaptureInProgress = false;
                final byte[] yuv = (byte[])msg.obj;
                final Thread preprocessThread = new Thread() {
                    @Override
                    public void run() {
                        GrayImage img = new GrayImage(yuv, mPreviewWidth, mPreviewHeight);
                        long startTime = System.currentTimeMillis();
                        Rect ext = makeTargetRect();
                        GrayImage binImg = findWordExtent(img, ext);
                        Log.i(TAG, "Find word extent in " + (System.currentTimeMillis() - startTime) + "msec");
                        Log.i(TAG, "Extent is " + ext.top + "," + ext.left + "," + ext.bottom + "," + ext.right);

                        try {
                            // Temporary
                            FileOutputStream os = new FileOutputStream("/sdcard/bin_dump" + dumpCount + ".gray");
                            os.write(binImg.getData());
                            os.close();
                        } catch (IOException ioe) { }

                        startTime = System.currentTimeMillis();
                        Bitmap textBitmap = binImg.asBitmap(ext);
                        try {
                            // Temporary
                            FileOutputStream os = new FileOutputStream("/sdcard/word_dump" + dumpCount + ".png");
                            textBitmap.compress(CompressFormat.PNG, 80, os);
                            os.close();
                        } catch (IOException ioe) { }
                        Log.i(TAG, "Dump in " + (System.currentTimeMillis() - startTime) + "msec");
                        
                        ++dumpCount;

                        mPreviewCaptureInProgress = false; // FIXME - move back up
                        
                        Message msg = mHandler.obtainMessage(R.id.msg_text_bitmap, textBitmap);
                        mHandler.sendMessage(msg);
                    }
                };
                mStatusText.setText(R.string.status_preprocessing_text);
                preprocessThread.start();
                break;
            case R.id.msg_text_bitmap:
                final Bitmap textBitmap = (Bitmap)msg.obj;
                final WeOCRClient weOCRClient = OCRApplication.getOCRClient();
                final Thread ocrThread = new Thread() {
                    @Override
                    public void run () {
                        synchronized (weOCRClient) {
                            try {
                                String ocrText = weOCRClient.doOCR(textBitmap);
                                Message msg = mHandler.obtainMessage(R.id.msg_ocr_result, ocrText);
                                mHandler.sendMessage(msg);
                            } catch (IOException ioe) {
                                // TODO
                                Log.e(TAG, "WeOCR failed", ioe);
                            }
                        }
                    }
                };
                mStatusText.setText(R.string.status_processing_text);
                ocrThread.start();
                break;
            case R.id.msg_ocr_result:
                final String ocrText = (String)msg.obj;
                Log.i(TAG, "OCR result text: " + ocrText);
                // Toast fails from this thread
                //Toast.makeText(WordCaptureActivity.this, "OCR result: " + ocrText, Toast.LENGTH_LONG)
                //     .show();
                mStatusText.setText(R.string.status_finished_text);
                mResultText.setText(ocrText);
                mResultText.setVisibility(View.VISIBLE);
                mHandler.sendEmptyMessageDelayed(R.id.msg_reset_status, 2000L);
                break;
            case R.id.msg_reset_status:
                //mResultText.setVisibility(View.INVISIBLE);
                mStatusText.setText(R.string.status_guide_text);
                break;
            default:
                super.handleMessage(msg);
            }
        }
    };
    
    private static final float TARGET_HEIGHT_FRACTION = 0.033f;
    private static final float TARGET_WIDTH_FRACTION = 0.021f;
        
    private Rect makeTargetRect () {
        int halfWidth = (int)(TARGET_WIDTH_FRACTION * mPreviewWidth / 2.0f);
        int halfHeight = (int)(TARGET_HEIGHT_FRACTION * mPreviewHeight / 2.0f);
        int centerX = mPreviewHeight / 2;
        int centerY = mPreviewWidth / 2;
        return new Rect(centerY - halfWidth, centerX - halfHeight, 
                    centerY + halfWidth, centerX + halfHeight);
    }
    
    private static final SimpleStructuringElement sHStrel = SimpleStructuringElement.makeHorizontal(2);
    private static final SimpleStructuringElement sVStrel = SimpleStructuringElement.makeVertical(2);
    
    // FIXME make this return extracted Bitmap
    private static final GrayImage findWordExtent (GrayImage img, Rect ext) {
        // Adaptive threshold
        float imgMean = img.mean();
        Log.i(TAG, "Image mean = " + imgMean);
        byte hi, lo;
        if (imgMean > 96) { // Arbitrary threshold
            // Most likely dark text on light background
            hi = (byte)255; 
            lo = (byte)0;
        } else {
            // Most likely light text on dark background
            hi = (byte)0;
            lo = (byte)255;
        }
        GrayImage tmpImg = img.meanFilter(10);  // Temporarily store local means here
        int threshOffset = (int)(0.33 * Math.sqrt(img.variance()));  // 0.33 pulled out of my butt
        GrayImage resultImg = img.adaptiveThreshold(hi, lo, threshOffset, tmpImg);
        
        // Dilate; it's grayscale, so we should use erosion instead
        resultImg.erode(sHStrel, tmpImg);
        GrayImage binImg = tmpImg.erode(sVStrel);

        // Find word extents
        int left = ext.left, right = ext.right, top = ext.top, bottom = ext.bottom;
        int imgWidth = img.getWidth(), imgHeight = img.getHeight();
        boolean extended;
        do {
            extended = false;
            
            if ((top - 1 >= 0) && binImg.min(left, top - 1, right, top) == 0) {
                --top;
                extended = true;
            }
            if ((bottom + 1 < imgHeight) && binImg.min(left, bottom, right, bottom + 1) == 0) {
                ++bottom;
                extended = true;
            }
            if ((left - 1 >= 0) && binImg.min(left - 1, top, left, bottom) == 0) {
                --left;
                extended = true;
            }
            if ((right + 1 < imgWidth) && binImg.min(right, top, right + 1, bottom) == 0) {
                ++right;
                extended = true;
            }
        } while (extended);
        ext.set(left, top, right, bottom);            
        
        return resultImg;
    }

}
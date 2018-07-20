package screenshot.mediaprojection;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getName();
    private static final int REQUEST_CODE = 100;
    private static String STORE_DIRECTORY;
    private static int IMAGES_PRODUCED;
    private String file;
    private static final String SCREENCAP_NAME = "screencap";
    private static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static MediaProjection sMediaProjection;
    private boolean mediaProjectionSnapshot = false;
    private ImageView imageViewResult;
    private TextView tvOutput;


    private MediaProjectionManager mProjectionManager;
    private ImageReader mImageReader;
    private Handler mHandler;
    private Display mDisplay;
    private VirtualDisplay mVirtualDisplay;
    private int mDensity;
    private int mWidth;
    private int mHeight;
    private int mRotation;
    private OrientationChangeCallback mOrientationChangeCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageViewResult = (ImageView) findViewById(R.id.ivResult);
        tvOutput = (TextView) findViewById(R.id.tvOutput);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

            Button startButton = (Button) findViewById(R.id.btStart);
            startButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mediaProjectionSnapshot = false;
                    startProjection();
                }
            });
        } else {
            tvOutput.setText("Media Projection needs the API version above Lollipop.");
        }
    }

    private void startProjection() {
        Thread thread = new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mHandler = new Handler();
                Looper.loop();
            }
        };
        thread.start();
        startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_CODE);
    }

    private void stopProjection() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (sMediaProjection != null) {
                    sMediaProjection.stop();
                }
            }
        });
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        //if (requestCode == REQUEST_CODE) {
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, "Screen Cast Permission Denied", Toast.LENGTH_SHORT).show();
        } else {
            sMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);

            if (sMediaProjection != null) {
                File externalFilesDir = getExternalFilesDir(null);
                if (externalFilesDir != null) {
                    STORE_DIRECTORY = externalFilesDir.getAbsolutePath() + "/screenshots/";
                    File storeDirectory = new File(STORE_DIRECTORY);
                    if (!storeDirectory.exists()) {
                        boolean success = storeDirectory.mkdirs();
                        if (!success) {
                            Log.e(TAG, "failed to create file storage directory.");
                            return;
                        }
                    }
                } else {
                    Log.e(TAG, "failed to create file storage directory, getExternalFilesDir is null.");
                    return;
                }

                // display metrics
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                mDensity = metrics.densityDpi;
                mDisplay = getWindowManager().getDefaultDisplay();

                // create virtual display depending on device width / height
                createVirtualDisplay();

                // register orientation change callback
                mOrientationChangeCallback = new OrientationChangeCallback(MainActivity.this);
                if (mOrientationChangeCallback.canDetectOrientation()) {
                    mOrientationChangeCallback.enable();
                }

                // register media projection stop callback
                sMediaProjection.registerCallback(new MediaProjectionStopCallback(), mHandler);
                Toast.makeText(this, "Screen Cast Permission Granted", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void createVirtualDisplay() {

        Point size = new Point();
        //mDisplay.getSize(size);
        mDisplay.getRealSize(size);
        mWidth = size.x;
        mHeight = size.y;

        // start capture reader
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = sMediaProjection.createVirtualDisplay(SCREENCAP_NAME, mWidth, mHeight, mDensity, VIRTUAL_DISPLAY_FLAGS, mImageReader.getSurface(), null, mHandler);
        mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mHandler);
    }


    public static Bitmap getScaledBitmap(final String absolutePath, final int sampleSize) {
        if (absolutePath != null) {
            String filepath = absolutePath;
            if (!absolutePath.contains(".")) {
                filepath = absolutePath + ".jpg";
            }

            int inWidth = 0;
            int inHeight = 0;

            final Runtime runtime = Runtime.getRuntime();
            final long usedMemInMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
            final long maxHeapSizeInMB = runtime.maxMemory() / 1048576L;

            final int minSampleSize = sampleSize > 0 ? sampleSize : (maxHeapSizeInMB - usedMemInMB < maxHeapSizeInMB / 10 ? 4 : 2);

            try {
                InputStream in = new FileInputStream(filepath);

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(in, null, options);
                in.close();

                // save width and height
                inWidth = options.outWidth;
                inHeight = options.outHeight;

                // decode full image pre-resized
                in = new FileInputStream(absolutePath);
                options = new BitmapFactory.Options();

                // calc rough re-size (this is no exact resize)
                Pair<Integer, Integer> imageDestinationMeasurements = getScaledImageMeasurements(inHeight, inWidth);
                options.inSampleSize = Math.max(Math.max(inWidth / imageDestinationMeasurements.second, inHeight / imageDestinationMeasurements.first), minSampleSize);

                // decode full image
                return BitmapFactory.decodeStream(in, null, options);
            } catch (IOException e) {
                Log.e("Error image", e.getMessage(), e);
            }
        }
        return null;
    }

    private static Pair<Integer, Integer> getScaledImageMeasurements(final int height, final int width) {
        final int maxResolution = 1600;
        int imageHeight = height;
        int imageWidth = width;

        if (imageWidth > maxResolution || imageHeight > maxResolution) {
            final float ratio = (float) imageWidth / (float) imageHeight;
            if (ratio > 1) {
                imageWidth = maxResolution;
                imageHeight = Math.round(imageWidth / ratio);
            } else {
                imageHeight = maxResolution;
                imageWidth = Math.round(imageHeight * ratio);
            }
        }

        return new Pair<>(imageHeight, imageWidth);
    }


    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            FileOutputStream fos = null;
            Bitmap bitmap = null;
            Bitmap croppedBitmap = null;

            try {
                image = reader.acquireLatestImage();
                if (image != null && !mediaProjectionSnapshot) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * mWidth;

                    bitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);

                    Rect frame = new Rect();
                    getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
                    int statusBarHeight = frame.top;
                    int navBarHeight = frame.bottom;

                    Rect rect = image.getCropRect();
                    Log.e(TAG, "captured image: " + IMAGES_PRODUCED + ", rowPadding=" + rowPadding + ", pixelStride=" + pixelStride + "; statusBarHeight=" + statusBarHeight + ", rect.left=" + rect.left + ", rect.top=" + rect.top + ", bitmap.height=" + bitmap.getHeight() + ", rect.height()=" + rect.height() + ", navBarHeight=" + navBarHeight);
                    //croppedBitmap = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height());
                    croppedBitmap = Bitmap.createBitmap(bitmap, rect.left, statusBarHeight, rect.width(), navBarHeight - statusBarHeight);

                    file = STORE_DIRECTORY + "/myscreen_" + IMAGES_PRODUCED + ".jng";
                    fos = new FileOutputStream(file);
                    croppedBitmap.compress(CompressFormat.JPEG, 90, fos);

                    IMAGES_PRODUCED++;

                    mediaProjectionSnapshot = true;
                    stopProjection();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                }
                if (bitmap != null) bitmap.recycle();
                if (croppedBitmap != null) croppedBitmap.recycle();
                if (image != null) image.close();
            }
        }
    }

    private class OrientationChangeCallback extends OrientationEventListener {

        OrientationChangeCallback(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            final int rotation = mDisplay.getRotation();
            if (rotation != mRotation) {
                mRotation = rotation;
                try {
                    // clean up
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);

                    // re-create virtual display depending on device width / height
                    createVirtualDisplay();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class MediaProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.e("ScreenCapture", "stopping projection.");
            Toast.makeText(getBaseContext(), "Screen Cast stopped", Toast.LENGTH_SHORT).show();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);
                    if (mOrientationChangeCallback != null) mOrientationChangeCallback.disable();
                    sMediaProjection.unregisterCallback(MediaProjectionStopCallback.this);

                }
            });

            // to update our UI thread
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Bitmap bitmap = getScaledBitmap(file, 1);
                    if (bitmap != null) {
                        Log.d(TAG, file + ", width=" + bitmap.getWidth() + ", bytes:" + bitmap.getByteCount());
                        imageViewResult.setImageBitmap(bitmap);
                    }
                }
            });
        }
    }


}

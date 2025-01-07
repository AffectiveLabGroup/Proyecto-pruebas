package com.example.sanbotapp.robotControl;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.sanbotapp.R;
import com.google.gson.Gson;
import com.qihancloud.opensdk.base.TopBaseActivity;
import com.qihancloud.opensdk.beans.FuncConstant;
import com.qihancloud.opensdk.function.beans.FaceRecognizeBean;
import com.qihancloud.opensdk.function.beans.StreamOption;
import com.qihancloud.opensdk.function.unit.MediaManager;
import com.qihancloud.opensdk.function.unit.SpeechManager;
import com.qihancloud.opensdk.function.unit.interfaces.media.FaceRecognizeListener;
import com.qihancloud.opensdk.function.unit.interfaces.media.MediaStreamListener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * className: MediaControlActivity
 * function: 多媒体控制
 * <p/> 人脸识别和抓图功能 需要安装“家庭成员app”
 * create at 2017/5/25 9:25
 *
 * @author gangpeng
 */

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
public class MediaControlActivity extends TopBaseActivity implements TextureView.SurfaceTextureListener {

    private final static String TAG = MediaControlActivity.class.getSimpleName();

    SurfaceView svMedia;
    TextureView tvMedia;
    Button tvCapture;
    ImageView ivCapture;
    TextView tvFace;

    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private ImageView ivScreenshot;

    private MediaManager mediaManager;
    private SpeechManager speechManager;


    /**
     * 视频编解码器
     */
    MediaCodec mediaCodec;
    /**
     * 视频编解码器超时时间
     */
    long decodeTimeout = 16000;
    MediaCodec.BufferInfo videoBufferInfo = new MediaCodec.BufferInfo();
    ByteBuffer[] videoInputBuffers;

    @Override
    public void onCreate(Bundle savedInstanceState) {

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);
        onMainServiceConnected();
        setContentView(R.layout.activity_media_control);
        //初始化变量
        mediaManager = (MediaManager) getUnitManager(FuncConstant.MEDIA_MANAGER);
        //svMedia = findViewById(R.id.sv_media);
        tvMedia = findViewById(R.id.tv_media);
        tvCapture = findViewById(R.id.tv_capture);
        ivScreenshot = findViewById(R.id.iv_screenshot);

        // Añadimos el speechManager
        speechManager = (SpeechManager) getUnitManager(FuncConstant.SPEECH_MANAGER);

        //svMedia.getHolder().addCallback(this);
        // Set TextureView listener
        tvMedia.setSurfaceTextureListener(this);
        initListener();

        // Capturar imagen
        tvCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //onViewClicked();
                // Tomar captura de pantalla
                //Bitmap screenshot = takeScreenshotFromSurfaceView(svMedia);
                Bitmap screenshot = takeScreenshotFromTextureView(tvMedia);

                if (screenshot != null) {
                    // Mostrar la captura en el ImageView
                    //ivScreenshot.setImageBitmap(screenshot);
                    //ivScreenshot.setVisibility(View.VISIBLE);

                    // Guardar captura en archivo
                    saveScreenshot(MediaControlActivity.this, screenshot);

                    // Mostrar mensaje
                    Toast.makeText(MediaControlActivity.this, "Captura mostrada y guardada", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MediaControlActivity.this, "Error al capturar la imagen", Toast.LENGTH_SHORT).show();
                }

            }
        });

    }

    public Bitmap takeScreenshotFromTextureView(TextureView textureView) {
        if (textureView.isAvailable()) {
            return textureView.getBitmap();
        }
        return null;
    }

    public Bitmap takeScreenshotFromSurfaceView(SurfaceView surfaceView) {
        // Create a Bitmap based on the size of the SurfaceView
        Bitmap bitmap = Bitmap.createBitmap(surfaceView.getWidth(), surfaceView.getHeight(), Bitmap.Config.ARGB_8888);

        // Get the Canvas from the SurfaceView's holder
        Canvas canvas = surfaceView.getHolder().lockCanvas();

        // Check if the Canvas is valid
        if (canvas != null) {
            try {
                // Copy the content of the Canvas to the Bitmap
                bitmap = Bitmap.createBitmap(surfaceView.getWidth(), surfaceView.getHeight(), Bitmap.Config.ARGB_8888);
                surfaceView.getHolder().getSurface().unlockCanvasAndPost(canvas);

                // Create a new Canvas to draw the content into the Bitmap
                Canvas bitmapCanvas = new Canvas(bitmap);
                surfaceView.getHolder().getSurface().lockCanvas(null);

                // Finally, draw the Canvas content to the Bitmap
                bitmapCanvas.drawBitmap(bitmap, 0, 0, null);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return bitmap;
    }




    public void saveScreenshot(Context context, Bitmap bitmap) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "screenshot_" + System.currentTimeMillis() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");

        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        try {
            if (uri != null) {
                OutputStream outputStream = context.getContentResolver().openOutputStream(uri);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                if (outputStream != null) {
                    outputStream.flush();
                    outputStream.close();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }



    /**
     * 初始化监听器
     */
    private void initListener() {
        mediaManager.setMediaListener(new MediaStreamListener() {
            @Override
            public void getVideoStream(byte[] bytes) {
                showViewData(ByteBuffer.wrap(bytes));
            }

            @Override
            public void getAudioStream(byte[] bytes) {
            }
        });
        mediaManager.setMediaListener(new FaceRecognizeListener() {
            @Override
            public void recognizeResult(List<FaceRecognizeBean> list) {
                StringBuilder sb = new StringBuilder();
                for (FaceRecognizeBean bean : list) {
                    sb.append(new Gson().toJson(bean));
                    sb.append("\n");


                    // Acceder al valor de la propiedad "user"
                    String user = bean.getUser();
                    // Hacer algo con el valor de "user"
                    System.out.println("Usuario reconocido: " + user);

                    if(user != ""){
                        speechManager.startSpeak("hola " + user + " ¿cómo estás?");
                    }

                }
                tvFace.setText(sb.toString());
                System.out.println("Persona reconocida????：" + sb.toString());


            }
        });
    }

    /**
     * 显示视频流
     *
     * @param sampleData
     */
    private void showViewData(ByteBuffer sampleData) {
        try {
            int inIndex = mediaCodec.dequeueInputBuffer(decodeTimeout);
            if (inIndex >= 0) {
                ByteBuffer buffer = videoInputBuffers[inIndex];
                int sampleSize = sampleData.limit();
                buffer.clear();
                buffer.put(sampleData);
                buffer.flip();
                mediaCodec.queueInputBuffer(inIndex, 0, sampleSize, 0, 0);
            }
            int outputBufferId = mediaCodec.dequeueOutputBuffer(videoBufferInfo, decodeTimeout);
            if (outputBufferId >= 0) {
                mediaCodec.releaseOutputBuffer(outputBufferId, true);
            } else {
                Log.e(TAG, "dequeueOutputBuffer() error");
            }

        } catch (Exception e) {
            Log.e(TAG, "发生错误", e);
        }
    }

    // TextureView.SurfaceTextureListener methods
    // TextureView.SurfaceTextureListener methods
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        this.surfaceTexture = surfaceTexture;
        this.surface = new Surface(surfaceTexture);

        // Configure stream options and open media stream
        StreamOption streamOption = new StreamOption();
        streamOption.setChannel(StreamOption.MAIN_STREAM);
        streamOption.setDecodType(StreamOption.HARDWARE_DECODE);
        streamOption.setJustIframe(false);
        mediaManager.openStream(streamOption);

        // Configure MediaCodec
        startDecoding(this.surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        // Handle size changes if necessary
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        // Close media stream and stop decoding
        mediaManager.closeStream();
        stopDecoding();
        if (surface != null) {
            surface.release();
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // Called when the content of the TextureView is updated
    }


    @Override
    protected void onMainServiceConnected() {

    }

    /*
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        //设置参数并打开媒体流
        StreamOption streamOption = new StreamOption();
        streamOption.setChannel(StreamOption.MAIN_STREAM);
        streamOption.setDecodType(StreamOption.HARDWARE_DECODE);
        streamOption.setJustIframe(false);
        mediaManager.openStream(streamOption);
        //配置MediaCodec
        startDecoding(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        //关闭媒体流
        mediaManager.closeStream();
        stopDecoding();
    }*/

    /**
     * 初始化视频编解码器
     *
     * @param surface
     */
    private void startDecoding(Surface surface) {
        if (mediaCodec != null) {
            return;
        }
        try {
            mediaCodec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat(
                    "video/avc", 1280, 720);
            mediaCodec.configure(format, surface, null, 0);
            mediaCodec.start();
            videoInputBuffers = mediaCodec.getInputBuffers();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * 结束视频编解码器
     */
    private void stopDecoding() {
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
            Log.i(TAG, "stopDecoding");
        }
        videoInputBuffers = null;
    }


    // FUNCION QUE SE REALIZA EN EL ONCLICK DE CAPTURA, LO QUE SE VA A INTENTAR ES O QUE HAGA UNA FOTO O GRABE UN VÍDEO
    public void onViewClicked() {
        //storeImage(mediaManager.getVideoImage());
        //ivCapture.setImageBitmap(mediaManager.getVideoImage());
        saveScreenshot(MediaControlActivity.this, mediaManager.getVideoImage());
    }

    public void storeImage(Bitmap bitmap){
        String dir = Environment.getExternalStorageDirectory()+ "/FACE_REG/IMG/" + "DCIM/";

        System.out.println("RUTA: " + dir);
        final File f = new File(dir);
        if (!f.exists()) {
            f.mkdirs();
        }
        String fileName = System.currentTimeMillis() + ".jpg";
        File file = new File(f, fileName);

        try {
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            System.out.println("Imagen guardada en: " + file.getAbsolutePath());
            fos.flush();
            fos.close();
            System.out.println("Imagen guardada en: " + file.getAbsolutePath());
        } catch (FileNotFoundException e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        }

    }
}

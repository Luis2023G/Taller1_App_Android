package com.example.myapplication;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.graphics.Bitmap;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
//import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.content.FileProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity"; // Tag for logging

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private Button callApiButton, callCameraButton;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private ImageReader imageReader;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private String cameraId;
    private TextView responseTextView;
    private AnthropicClient client;
    private ExecutorService executorService; // Executor for background tasks

    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private Uri photoUri;
    private ActivityResultLauncher<Uri> takePictureLauncher;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri imageUri = result.getData().getData();
                        processImage(imageUri);  // Llama a tu función para procesar la imagen
                    }
                }
        );

        surfaceView = findViewById(R.id.surfaceView);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        // Initialize Views
        callApiButton = findViewById(R.id.call_api_button);
        callCameraButton = findViewById(R.id.camera_button);
        responseTextView = findViewById(R.id.response_textview);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            setupSurfaceView();
        }
        // Initialize Anthropic Client
        // WARNING: Hardcoded API Key - Not recommended for production
        client = AnthropicOkHttpClient.builder()
                .apiKey("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")
                .build();

        // Initialize Executor Service
        executorService = Executors.newSingleThreadExecutor();

        // Handle Window Insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Set Button Click Listener
        callApiButton.setOnClickListener(v -> {
            //responseTextView.setText("Llamando a la API..."); // Indicate loading
            //callAnthropicApi();
            openGallery();
        });
        callCameraButton.setOnClickListener(v -> {takePicture();});
    }

    private void setupSurfaceView() {
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                openCamera();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                closeCamera();
            }
        });
    }
    private static final int PICK_IMAGE_REQUEST = 1;
    private Uri imageUri;

    private void openCamera() {
        try {
            cameraId = cameraManager.getCameraIdList()[0]; // Obtener la primera cámara
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(this::saveImage, new Handler(Looper.getMainLooper()));

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    private void createCameraPreview() {
        try {
            Surface surface = surfaceHolder.getSurface();
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(MainActivity.this, "Configuración de cámara fallida", Toast.LENGTH_SHORT).show();
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void takePicture() {
        if (cameraDevice == null || captureSession == null) {
            Toast.makeText(this, "Cámara no está lista", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            captureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(MainActivity.this, "Foto capturada", Toast.LENGTH_SHORT).show();
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void saveImage(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image != null) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            image.close();
            String base64Image = Base64.getEncoder().encodeToString(bytes);
            sendImageToAnthropic(base64Image);
            // Aquí puedes guardar la imagen o convertirla a Base64 para enviarla a la API
            Log.d(TAG, "Imagen capturada y convertida a byte array.");
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }


    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/jpeg");
        imagePickerLauncher.launch(intent); // Nuevo método en lugar de startActivityForResult
    }



    private void processImage(Uri imageUri) {
        try {
            String base64Image = encodeImageToBase64(imageUri);
            if (base64Image != null) {
                sendImageToAnthropic(base64Image);
            } else {
                responseTextView.setText("Error al codificar la imagen.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            responseTextView.setText("Error al procesar la imagen.");
        }
    }
    private String encodeImageToBase64(Uri uri) {

        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            byte[] bytes = new byte[inputStream.available()];
            inputStream.read(bytes);
            String imgBase64 = Base64.getEncoder().encodeToString(bytes);
            return imgBase64;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void sendImageToAnthropic(String imgBase64) {
        executorService.execute(() -> {
            try {

                ContentBlockParam imageParam = ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(Base64ImageSource.builder()
                                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                .data(imgBase64)
                                .build())
                        .build());
                MessageCreateParams params = MessageCreateParams.builder()
                        .model(Model.CLAUDE_3_5_SONNET_LATEST)
                        .addUserMessage("Por favor, analiza esta imagen de documento comercial y extrae todos los datos relevantes. Estructura la información en un formato JSON con los campos más importantes de las siguientes entidades: metadatos (incluir tipo de documento), emisor, receptor, items, totales.")
                        .addUserMessageOfBlockParams(List.of(imageParam))
                        .maxTokens(2048)
                        .build();

                // Make the API call
                List<ContentBlock> contentBlocks = client.messages().create(params).content();
                for (ContentBlock block : contentBlocks) {
                    updateResponseTextView("Respuesta de la API:\n" + block.asText().text());
                }

            } catch (Exception e) {
                Log.e(TAG, "Error calling Anthropic API", e);
                updateResponseTextView("Error al llamar a la API: " + e.getMessage());
            }
        });
    }


    private void callAnthropicApi() {
        executorService.execute(() -> {
            try {
                // Prepare the API request
                MessageCreateParams params = MessageCreateParams.builder()
                        .model(Model.CLAUDE_3_5_SONNET_LATEST) // Or another suitable model
                        .maxTokens(1000)
                        .addUserMessage("HOla ¿Cómo estas?")
                        .build();

                // Make the API call
                // com.anthropic.models.messages.MessagesResult result = client.messages().create(params); // Use the correct result type
                List<ContentBlock> contentBlocks = client.messages().create(params).content();
                for (ContentBlock block : contentBlocks) {
                    updateResponseTextView("Respuesta de la API:\n" + block.asText().text());
                }


            } catch (Exception e) {
                Log.e(TAG, "Error calling Anthropic API", e);
                updateResponseTextView("Error al llamar a la API: " + e.getMessage());
            }
            });
    }

    // Helper method to update TextView on the main thread
    private void updateResponseTextView(final String text) {
        new Handler(Looper.getMainLooper()).post(() -> responseTextView.setText(text));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
        // Shutdown executor service
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
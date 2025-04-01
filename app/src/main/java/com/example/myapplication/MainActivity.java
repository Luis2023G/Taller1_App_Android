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
    
    // Constante para propósitos de registro
    private static final String TAG = "MainActivity"; // Tag for logging

    // Constantes para los permisos de la cámara
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;

    // Elementos de la interfaz de usuario
    private Button callApiButton, callCameraButton;
    private TextView responseTextView;

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
    
    // Cliente de la API y ejecutor para tareas en segundo plano
    private AnthropicClient client;
    private ExecutorService executorService; // Executor for background tasks

    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private Uri photoUri;
    private ActivityResultLauncher<Uri> takePictureLauncher;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this); // Maneja la visualización en pantalla completa
        setContentView(R.layout.activity_main);

        // Inicializa el selector de imágenes para la galería
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri imageUri = result.getData().getData();
                        processImage(imageUri);  // Llama a tu función para procesar la imagen
                    }
                }
        );

        // Inicializa los elementos de la interfaz de usuario
        surfaceView = findViewById(R.id.surfaceView);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        // Initialize Views
        callApiButton = findViewById(R.id.call_api_button);
        callCameraButton = findViewById(R.id.camera_button);
        responseTextView = findViewById(R.id.response_textview);

        // Verifica y solicita el permiso de la cámara si es necesario
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            setupSurfaceView(); // Configura el SurfaceView para la vista previa de la cámara
        }
        
        // Inicializa el cliente de Anthropic (clave de API codificada - evitar en producción)
        client = AnthropicOkHttpClient.builder()
                .apiKey("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")
                .build();

        // Inicializa el servicio ejecutor para tareas en segundo plano
        executorService = Executors.newSingleThreadExecutor();

        // Maneja los márgenes de la ventana (ajusta para las barras del sistema)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Establece los listeners para los botones
        callApiButton.setOnClickListener(v -> {
            openGallery(); // Abre la galería para seleccionar una imagen
        });
        callCameraButton.setOnClickListener(v -> {takePicture();}); // Captura una imagen con la cámara
    }

    /**
     * Configura el SurfaceView para la vista previa de la cámara.
     */
    private void setupSurfaceView() {
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                openCamera(); // Abre la cámara cuando se crea el surface
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                // Maneja los cambios en el surface (redimensionar, cambio de formato, etc.)
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                closeCamera(); // Cierra la cámara cuando el surface es destruido
            }
        });
    }
    private static final int PICK_IMAGE_REQUEST = 1;
    private Uri imageUri;

    // Abre la cámara y comienza la vista previa.
    private void openCamera() {
        try {
            cameraId = cameraManager.getCameraIdList()[0]; // Selecciona la cámara trasera
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(this::saveImage, new Handler(Looper.getMainLooper()));

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, stateCallback, backgroundHandler); // Abre la cámara en un hilo de fondo
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Callback para la apertura de la cámara.
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

    // Toma una foto utilizando la cámara.
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
            // Convertir la imagen capturada a Base64
            String base64Image = Base64.getEncoder().encodeToString(bytes);
            sendImageToAnthropic(base64Image);
            // Aquí puedes guardar la imagen o convertirla a Base64 para enviarla a la API
            Log.d(TAG, "Imagen capturada y convertida a byte array.");
        }
    }

    // Cierra la cámara y libera los recursos asociados.
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

    //  Abre la galería de imágenes para que el usuario pueda seleccionar una.
    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/jpeg");
        imagePickerLauncher.launch(intent); // Nuevo método en lugar de startActivityForResult
    }



    private void processImage(Uri imageUri) {
        try {
            // Convierte la imagen a Base64
            String base64Image = encodeImageToBase64(imageUri);
            // Si la codificación es exitosa, la enviamos a la API, si no, mostramos un error
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
            // Abre un InputStream para leer los datos de la imagen
            InputStream inputStream = getContentResolver().openInputStream(uri);

            // Convierte la imagen a un arreglo de bytes
            byte[] bytes = new byte[inputStream.available()];
            inputStream.read(bytes);

            // Codifica el arreglo de bytes a Base64 y lo retorna
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
                // Construye el parámetro de la imagen para enviar a la API
                ContentBlockParam imageParam = ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(Base64ImageSource.builder()
                                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                .data(imgBase64)
                                .build())
                        .build());

                // Prepara la solicitud para la API
                MessageCreateParams params = MessageCreateParams.builder()
                        .model(Model.CLAUDE_3_5_SONNET_LATEST)
                        .addUserMessage("Por favor, analiza esta imagen de documento comercial y extrae todos los datos relevantes. Estructura la información en un formato JSON con los campos más importantes de las siguientes entidades: metadatos (incluir tipo de documento), emisor, receptor, items, totales.")
                        .addUserMessageOfBlockParams(List.of(imageParam))
                        .maxTokens(2048)
                        .build();

                // Realiza la llamada a la API
                List<ContentBlock> contentBlocks = client.messages().create(params).content();
                for (ContentBlock block : contentBlocks) {
                    // Actualiza la UI con la respuesta de la API
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
                // Prepara los parámetros de la API
                MessageCreateParams params = MessageCreateParams.builder()
                        .model(Model.CLAUDE_3_5_SONNET_LATEST) // Or another suitable model
                        .maxTokens(1000)
                        .addUserMessage("HOla ¿Cómo estas?")
                        .build();

                // Realiza la llamada a la API
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

    // Método para actualizar el TextView en el hilo principal
    private void updateResponseTextView(final String text) {
        new Handler(Looper.getMainLooper()).post(() -> responseTextView.setText(text));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera(); // Cierra la cámara
        
        // Apaga el executorService para evitar que siga ejecutando tareas después de que la actividad haya terminado
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}

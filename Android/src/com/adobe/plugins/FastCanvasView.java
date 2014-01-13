/**
 * Copyright 2012 Adobe Systems Incorporated
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.adobe.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES10;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceHolder;

public class FastCanvasView extends GLSurfaceView {

    private FastCanvasRenderer renderer;
    private LinkedList<TextureRequest> loadQueue = new LinkedList<TextureRequest>();
    private Map<Integer, Texture> textures = new HashMap<Integer, Texture>();
    private LinkedList<CallbackContext> frameRequests = new LinkedList<CallbackContext>();
    private String renderCommand;

    public FastCanvasView(Context context, FastCanvas plugin) {
        super(context);
        this.setEGLConfigChooser(false);// turn off the depth buffer
        this.renderer = new FastCanvasRenderer();
        this.setRenderer(renderer);
        this.setRenderMode(RENDERMODE_CONTINUOUSLY);

        this.setFocusableInTouchMode(true);
        this.requestFocus();
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surfaceCreated");
        super.surfaceCreated(holder);
    }

    @Override
    public void onPause() {
        Log.i(TAG, "onPause");
        FastCanvasJNI.contextLost();
        super.onPause();
    }

    @Override
    public void onResume() {
        Log.i(TAG, "onResume");

        for (Texture t : textures.values()) {
            TextureRequest m = new TextureRequest(t.url, t.id, null);
            Log.i(TAG, "queueing reload textures " + m.texture);
            loadQueue.add(m);
        }

        super.onResume();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Log.i(TAG, "surfaceChanged");
        super.surfaceChanged(holder, format, w, h);
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        // Context is gone, no GL calls after this
        Log.i(TAG, "surfaceDestroyed");
        super.surfaceDestroyed(holder);
        FastCanvasJNI.release();
    }

    public boolean execute(String action, JSONArray args,
            final CallbackContext callbackContext) throws JSONException {
        // Log.i(TAG, "execute: " + action);

        try {

            if (action.equals("render")) {
                renderCommand = args.getString(0);
                return true;

            } else if (action.equals("frameRequest")) {
                // Log.i(TAG, "frameRequest");

                frameRequests.offer(callbackContext);
                PluginResult result = new PluginResult(
                        PluginResult.Status.NO_RESULT);
                result.setKeepCallback(true);
                callbackContext.sendPluginResult(result);

                CallbackContext frameRequest;
                int size = frameRequests.size();
                while (size-- > 0
                        && (frameRequest = frameRequests.poll()) != null) {
                    // Log.i(TAG, "frameResponse");
                    PluginResult resultx = new PluginResult(
                            PluginResult.Status.OK);
                    resultx.setKeepCallback(false);
                    frameRequest.sendPluginResult(resultx);
                }

            } else if (action.equals("setBackgroundColor")) {
                String color = args.getString(0);
                Log.i(TAG, "setBackground: " + color);
                try {
                    int red = Integer.valueOf(color.substring(0, 2), 16);
                    int green = Integer.valueOf(color.substring(2, 4), 16);
                    int blue = Integer.valueOf(color.substring(4, 6), 16);
                    FastCanvasJNI.setBackgroundColor(red, green, blue);
                } catch (Exception e) {
                    Log.e(TAG, "Invalid background color: " + color, e);
                }

                return true;

            } else if (action.equals("loadTexture")) {
                TextureRequest m = new TextureRequest(args.getString(0),
                        args.getInt(1), callbackContext);
                Log.i(TAG, "queueing load texture " + m.texture);
                loadQueue.add(m);
                return true;

            } else if (action.equals("unloadTexture")) {
                Log.i(TAG, "unload texture");
                int id = args.getInt(0);
                textures.remove(id);
                FastCanvasJNI.removeTexture(id);
                checkError();

                return true;

            } else if (action.equals("setOrtho")) {
                int width = args.getInt(0);
                int height = args.getInt(1);

                Log.i(TAG, "setOrtho: " + width + ", " + height);
                FastCanvasJNI.setOrtho(width, height);

                return true;

            } else if (action.equals("capture")) {
                Log.i(TAG, "capture");

                // set the root path to /mnt/sdcard/
                String file = Environment.getExternalStorageDirectory()
                        + args.getString(4);
                File dir = new File(file).getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    callbackContext.sendPluginResult(new PluginResult(
                            PluginResult.Status.ERROR,
                            "Could not create directory"));
                    return true;
                }

                int x = args.optInt(0, 0);
                int y = args.optInt(1, 0);
                int width = args.optInt(2, -1);
                int height = args.optInt(3, -1);

                FastCanvasJNI.captureGLLayer(callbackContext.getCallbackId(),
                        x, y, width, height, file);

                return true;

            } else if (action.equals("isAvailable")) {
                callbackContext.sendPluginResult(new PluginResult(
                        PluginResult.Status.OK, true));
                return true;

            } else {
                Log.i(TAG, "invalid execute action: " + action);
            }

        } catch (Exception e) {
            Log.e(TAG, "error executing action: " + action + "(" + args + ")",
                    e);
        }

        return false;
    }

    public class FastCanvasRenderer implements GLSurfaceView.Renderer {

        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            Log.i(TAG, "onSurfaceCreated: " + gl + ", " + config);

            TextureRequest m;
            while ((m = loadQueue.poll()) != null) {
                loadTexture(m.callbackContext, m.texture);
            }

            IntBuffer ib = IntBuffer.allocate(100);
            ib.position(0);
            GLES10.glGetIntegerv(GLES10.GL_RED_BITS, ib);
            int red = ib.get(0);
            GLES10.glGetIntegerv(GLES10.GL_GREEN_BITS, ib);
            int green = ib.get(0);
            GLES10.glGetIntegerv(GLES10.GL_BLUE_BITS, ib);
            int blue = ib.get(0);
            GLES10.glGetIntegerv(GLES10.GL_STENCIL_BITS, ib);
            int stencil = ib.get(0);
            GLES10.glGetIntegerv(GLES10.GL_DEPTH_BITS, ib);
            int depth = ib.get(0);

            Log.i(TAG, "onSurfaceCreated R: " + red + " G: " + green + " B: "
                    + blue + " DEPETH: " + depth + " STENCIL: " + stencil);

        }

        public void onSurfaceChanged(GL10 gl, int width, int height) {
            Log.i(TAG, "onSurfaceChanged");
            FastCanvasJNI.surfaceChanged(width, height);
        }

        public void onDrawFrame(GL10 gl) {
            FastCanvasJNI.render(renderCommand);
            checkError();
        }

        private static final String TAG = "FastCanvasRenderer";
    }

    private void checkError() {
        int error = GLES10.glGetError();
        if (error != GLES10.GL_NO_ERROR) {
            Log.i(TAG, "glError=" + error);
        }
        assert error == GLES10.GL_NO_ERROR;
    }

    private void loadTexture(CallbackContext callback, Texture texture) {
        Log.i(TAG, "load texture: " + texture);

        if (textures.containsKey(texture.id)) {
            FastCanvasJNI.removeTexture(texture.id);
            checkError();
        }

        try {
            FastCanvasTextureDimension dim = loadTexture(texture);

            if (callback != null) {
                textures.put(texture.id, texture);
                JSONArray args = new JSONArray();
                args.put(dim.width);
                args.put(dim.height);
                callback.success(args);
            }

        } catch (Exception e) {
            Log.i(TAG, "load texture error: ", e);
            if (callback != null) {
                callback.error(e.getMessage());
            }
        }
    }

    private FastCanvasTextureDimension loadTexture(Texture texture)
            throws IOException {

        FastCanvasTextureDimension dim = new FastCanvasTextureDimension();

        String path = "www/" + texture.url;

        AssetManager assets = getContext().getAssets();

        // PNG files with premultiplied
        // alpha and GLUtils don't get along
        // http://stackoverflow.com/questions/3921685
        if (path.toLowerCase(Locale.US).endsWith(".png")) {
            if (FastCanvasJNI.addPngTexture(assets, path, texture.id, dim)) {
                return dim;
            }
            Log.i(TAG, "native PNG load filaed, falling back to GLUtils");
        }

        Bitmap bmp = BitmapFactory.decodeStream(assets.open(path));

        int[] glID = new int[1];
        GLES10.glGenTextures(1, glID, 0);
        GLES10.glBindTexture(GLES10.GL_TEXTURE_2D, glID[0]);
        GLES10.glTexParameterf(GLES10.GL_TEXTURE_2D,
                GLES10.GL_TEXTURE_MIN_FILTER, GLES10.GL_LINEAR);
        GLES10.glTexParameterf(GLES10.GL_TEXTURE_2D,
                GLES10.GL_TEXTURE_MAG_FILTER, GLES10.GL_LINEAR);

        int width = bmp.getWidth(), height = bmp.getHeight();
        int p2width = 2, p2height = 2;
        while (p2width < width)
            p2width *= 2;
        while (p2height < height)
            p2height *= 2;

        if (width == p2width && height == p2height) {
            GLUtils.texImage2D(GLES10.GL_TEXTURE_2D, 0, bmp, 0);
        } else {
            Log.i(TAG, "load texture scaling texture " + texture
                    + " to power of 2");
            GLES10.glTexImage2D(GLES10.GL_TEXTURE_2D, 0, GLES10.GL_RGBA,
                    p2width, p2height, 0, GLES10.GL_RGBA,
                    GLES10.GL_UNSIGNED_BYTE, null);
            GLUtils.texSubImage2D(GLES10.GL_TEXTURE_2D, 0, 0, 0, bmp);
            width = p2width;
            height = p2height;
        }

        checkError();

        FastCanvasJNI.addTexture(texture.id, glID[0], width, height);
        Log.i(TAG, "load texture done: " + texture);

        dim.width = bmp.getWidth();
        dim.height = bmp.getHeight();

        return dim;
    }

    class Texture {
        public final String url;
        public final int id;

        public Texture(String url, int id) {
            this.url = url;
            this.id = id;
        }

        @Override
        public String toString() {
            return url + "#" + id + "@" + hashCode();
        }
    }

    class TextureRequest {
        public final Texture texture;
        public final CallbackContext callbackContext;

        public TextureRequest(String url, int id,
                CallbackContext callbackContext) {
            this.texture = new Texture(url, id);
            this.callbackContext = callbackContext;
        }
    }

    private static final String TAG = "FastCanvasView";
}
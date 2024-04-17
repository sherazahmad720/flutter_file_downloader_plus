package com.odehbros.flutter_file_downloader;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.odehbros.flutter_file_downloader.core.DownloadCallbacks;
import com.odehbros.flutter_file_downloader.core.DownloadTask;
import com.odehbros.flutter_file_downloader.errors.ErrorCodes;
import com.odehbros.flutter_file_downloader.errors.PermissionUndefinedException;
import com.odehbros.flutter_file_downloader.permissions.PermissionHandler;
import com.odehbros.flutter_file_downloader.permissions.PermissionStatus;

import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class MethodCallHandlerImpl implements MethodChannel.MethodCallHandler {

    private static final String CHANNEL = "com.abdallah.libs/file_downloader";
    private static final String TAG = "MethodCallHandlerImpl";
    private final PermissionHandler permissionManager;

    @Nullable
    private Context context;
    @Nullable
    private Activity activity;

    private final Map<Long, DownloadCallbacks> tasks = new HashMap<>();
    private final Map<String, StoreHelper> stored = new HashMap<>();

    MethodCallHandlerImpl(
            PermissionHandler permissionManager) {
        this.permissionManager = permissionManager;
    }

    @Nullable
    private MethodChannel channel;

    public StoreHelper findHelper(final long id) {
        final String toFind = String.valueOf(id);
        for (final String key : stored.keySet()) {
            if ((toFind + "").equals(stored.get(key).id + "")) return stored.get(key);
        }
        return null;
    }

    public void removeStoreHelper(final long id) {
        final String toFind = String.valueOf(id);
        for (final String key : stored.keySet()) {
            if ((toFind + "").equals(stored.get(key).id + "")) {
                stored.remove(key);
                return;
            }
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        final StoreHelper helper = new StoreHelper(call, result);
        stored.put(call.argument("key"), helper);
        switch (call.method) {
            case "requestPermission":
                onRequestPermission(helper, true);
                break;
            case "onStartDownloadingFile":
            case "downloadFile":
                onStartDownloadingFile(helper);
                break;
            case "cancelDownload":
                cancelDownload(helper);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    void startListening(Context context, BinaryMessenger messenger) {
        if (channel != null) {
            Log.w(TAG, "Setting a method call handler before the last was disposed.");
            stopListening();
        }

        channel = new MethodChannel(messenger, CHANNEL);
        channel.setMethodCallHandler(this);
        this.context = context;
    }

    void stopListening() {
        if (channel == null) {
            Log.d(TAG, "Tried to stop listening when no MethodChannel had been initialized.");
            return;
        }

        channel.setMethodCallHandler(null);
        channel = null;
    }

    void setActivity(@Nullable Activity activity) {
        this.activity = activity;
    }

    private void onRequestPermission(StoreHelper helper, final boolean sendResult) {
        try {
            permissionManager.requestPermissions(
//                    activity,
                    permission -> {
                        if (sendResult) {
                            helper.result.success(permission.toInt());
                            stored.remove(helper.call.argument("key"));
                        } else {
                            if (permission != PermissionStatus.always) {
                                ErrorCodes errorCode = ErrorCodes.permissionDenied;
                                helper.result.error(errorCode.toString(), errorCode.toDescription(), null);
                                stored.remove(helper.call.argument("key"));
                            } else {
                                onMethodCall(helper.call, helper.result);
                            }
                        }
                    },
                    (ErrorCodes errorCode) ->
                            helper.result.error(errorCode.toString(), errorCode.toDescription(), null));
        } catch (PermissionUndefinedException e) {
            ErrorCodes errorCode = ErrorCodes.permissionDefinitionsNotFound;
            helper.result.error(errorCode.toString(), errorCode.toDescription(), null);
        }
    }

    private void onStartDownloadingFile(StoreHelper helper) {
        if (permissionManager.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PermissionStatus.always) {
            onRequestPermission(helper, false);
            return;
        }

        String url = helper.call.argument("url");
        String name = helper.call.argument("name");
        String key = helper.call.argument("key");
        String subPath = helper.call.argument("subPath");
        String destination = helper.call.argument("download_destination");
        String service = helper.call.argument("download_service");
        String methodType = helper.call.argument("method_type");
        String notifications = helper.call.argument("notifications");
        Map<String, String> requestHeaders = helper.call.argument("headers");

        new DownloadTask(activity)
                .setUrl(url)
                .setName(name)
                .setSubPath(subPath)
                .setNotifications(notifications)
                .setRequestHeaders(requestHeaders)
                .setDownloadDestination(destination)
                .setMethodType(methodType)
                .setDownloadService(service)
                .setHelper(helper)
                .setCallbacks(new DownloadCallbacks() {
                    @Override
                    public void onIDReceived(long id) {
                        super.onIDReceived(id);
                        tasks.put(id, this);

                        Map<String, Object> args = new HashMap();

                        args.put("id", id);
                        args.put("url", helper.call.argument("url"));
                        args.put("key", helper.call.argument("key"));
                        stored.get(key).id = String.valueOf(id);
                        channel.invokeMethod("onIDReceived", args);
                    }

                    @Override
                    public void onProgress(double progress) {
                        super.onProgress(progress);

                        Map<String, Object> args = new HashMap();

                        args.put("id", id);
                        args.put("progress", progress);
                        args.put("key", key);
                        channel.invokeMethod("onProgress", args);
                    }

                    @Override
                    public void onProgress(String name, double progress) {
                        super.onProgress(name, progress);

                        Map<String, Object> args = new HashMap();

                        args.put("id", id);
                        args.put("name", name);
                        args.put("progress", progress);
                        args.put("key", key);
                        channel.invokeMethod("onProgress", args);
                    }

                    @Override
                    public void onDownloadCompleted(String path) {
                        super.onDownloadCompleted(path);
                        PluginLogger.log("Download "+id+" has completed, path: "+path);

                        Map<String, Object> args = new HashMap();

                        args.put("id", id);
                        args.put("path", path);
                        args.put("key", key);
                        channel.invokeMethod("onDownloadCompleted", args);

                        removeTask(id);
                    }

                    @Override
                    public void onDownloadError(String errorMessage) {
                        super.onDownloadError(errorMessage);

                        Map<String, Object> args = new HashMap();

                        args.put("id", id);
                        args.put("error", errorMessage);
                        args.put("key", helper.call.argument("key"));
                        channel.invokeMethod("onDownloadError", args);

                        removeTask(id);
                    }
                })
                .setMethodCallHandler(this)
                .build()
                .startDownload();
    }

    private void removeTask(final long id) {
        tasks.remove(id);
    }

    private void cancelDownload(final StoreHelper helper) {
        final long id = Long.valueOf(helper.call.argument("id"));

        final DownloadCallbacks task = tasks.get(id);
        if (task == null) {
            helper.result.error(
                    "Download task not found",
                    "Could not find an active download task with id " + id,
                    null);
            return;
        }
        task.onDownloadError("Download canceled");
        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        final int removedCount = downloadManager.remove(id);
        helper.result.success(removedCount > 0);
    }

    public DownloadCallbacks getTask(final long id) {
        return tasks.get(id);
    }
}

package com.sherazi.flutter_file_downloader.permissions;

@FunctionalInterface
public interface PermissionResultCallback {
    public void onResult(PermissionStatus status);
}

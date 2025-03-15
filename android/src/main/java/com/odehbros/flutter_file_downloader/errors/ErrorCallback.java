package com.sherazi.flutter_file_downloader.errors;

@FunctionalInterface
public interface ErrorCallback {
    void onError(ErrorCodes errorCode);
}

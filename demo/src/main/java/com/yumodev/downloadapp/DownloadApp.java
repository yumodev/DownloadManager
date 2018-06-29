package com.yumodev.downloadapp;

import android.app.Application;

import com.ymdev.download.providers.DownloadManager;


/**
 * Created by yumo on 2018/6/29.
 */

public class DownloadApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DownloadManager.init(this);
    }
}

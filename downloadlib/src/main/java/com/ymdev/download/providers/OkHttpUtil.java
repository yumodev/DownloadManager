package com.ymdev.download.providers;

import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * Created by yumodev on 17/11/30.
 */

public class OkHttpUtil {
    public static final String TAG = "okhttp";
    private static OkHttpClient mOkHttpClient = null;
    public static OkHttpClient getOkHttpClient(){
        if (mOkHttpClient == null){
            synchronized (OkHttpUtil.class){

                HttpLoggingInterceptor logInterceptor = new HttpLoggingInterceptor();
                logInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

                mOkHttpClient = new OkHttpClient.Builder()
                        .addInterceptor(logInterceptor)
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .build();
            }
        }
        return mOkHttpClient;
    }

    private static class LogInterceptor implements Interceptor {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Log.d(TAG, "request:" + request.toString());
            long t1 = System.nanoTime();
            okhttp3.Response response = chain.proceed(chain.request());
            long t2 = System.nanoTime();
            Log.v(TAG, String.format(Locale.getDefault(), "Received response for %s in %.1fms%n%s",
                    response.request().url(), (t2 - t1) / 1e6d, response.headers()));
            okhttp3.MediaType mediaType = response.body().contentType();
            String content = response.body().string();
            Log.i(TAG, "response body:" + content);
            return response.newBuilder()
                    .body(okhttp3.ResponseBody.create(mediaType, content))
                    .build();
        }
    }
}

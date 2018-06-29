/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ymdev.download.providers;

import android.content.ContentValues;
import android.content.Context;
import android.os.PowerManager;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SyncFailedException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.ymdev.download.providers.Constants.TAG;

/**
 * Runs an actual download
 * 下载的最终实现类
 */
public class DownloadThread extends Thread {

    private Context mContext;
    private DownloadInfo mInfo;
    private SystemFacade mSystemFacade;

    public DownloadThread(Context context, SystemFacade systemFacade, DownloadInfo info) {
        mContext = context;
        mSystemFacade = systemFacade;
        mInfo = info;
    }

    /**
     * Returns the user agent provided by the initiating app, or use the default one
     */
    private String userAgent() {
        String userAgent = mInfo.mUserAgent;
        if (userAgent != null) {
        }
        if (userAgent == null) {
            userAgent = Constants.DEFAULT_USER_AGENT;
        }
        return userAgent;
    }

    /**
     * State for the entire run() method.
     */
    private static class State {
        public String mFilename;//文件名
        public FileOutputStream mStream;//文件输出流
        public String mMimeType;//文件mMimeType
        public boolean mCountRetry = false;
        public int mRetryAfter = 0;
        public int mRedirectCount = 0;
        public String mNewUri;//重定向后新的URL
        public boolean mGotData = false;
        public String mRequestUri;//初始请求的URI

        public State(DownloadInfo info) {
            mMimeType = sanitizeMimeType(info.mMimeType);
            mRedirectCount = info.mRedirectCount;
            mRequestUri = info.mUri;
            mFilename = info.mFileName;
        }
    }

    /**
     * State within executeDownload()
     * 下载过程中的状态
     */
    private static class InnerState {
        public int mBytesSoFar = 0;//目前下载了多少
        public String mHeaderETag;
        public boolean mContinuingDownload = false;//是否继续下载
        public String mHeaderContentLength;//请求返回的文件长度
        public String mHeaderContentDisposition;
        public String mHeaderContentLocation;
        public int mBytesNotified = 0;
        public long mTimeLastNotification = 0;
    }

    /**
     * Raised from methods called by run() to indicate that the current request should be stopped
     * immediately.
     *
     * Note the message passed to this exception will be logged and therefore must be guaranteed
     * not to contain any PII, meaning it generally can't include any information about the request
     * URI, headers, or destination filename.
     */
    private class StopRequest extends Throwable {
        public int mFinalStatus;

        public StopRequest(int finalStatus, String message) {
            super(message);
            mFinalStatus = finalStatus;
        }

        public StopRequest(int finalStatus, String message, Throwable throwable) {
            super(message, throwable);
            mFinalStatus = finalStatus;
        }
    }

    /**
     * Raised from methods called by executeDownload() to indicate that the download should be
     * retried immediately.
     */
    private class RetryDownload extends Throwable {}

    /**
     * Executes the download in a separate thread
     */
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        State state = new State(mInfo);
        OkHttpClient client = OkHttpUtil.getOkHttpClient();
        PowerManager.WakeLock wakeLock = null;
        int finalStatus = Downloads.Impl.STATUS_UNKNOWN_ERROR;

        try {
            //设置屏幕常量
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            wakeLock.acquire();


            if (Constants.LOGV) {
                Log.v(TAG, "initiating download for " + mInfo.mUri);
            }

            boolean finished = false;
            while(!finished) {
                Log.i(TAG, "Initiating request for download " + mInfo.mId);
                Request.Builder builder = new Request.Builder();
                builder.url(state.mRequestUri);

                InnerState innerState = new InnerState();


                setupDestinationFile(state, innerState);
                addRequestHeaders(innerState, builder);

                // check just before sending the request to avoid using an invalid connection at all
                checkConnectivity(state);

                Call call = null;
                try {
                    call = client.newCall(builder.build());
                    executeDownload(state, innerState, call);
                    finished = true;
                } catch (RetryDownload exc) {
                    // fall through
                } finally {
                   call.cancel();
                }
            }

            if (Constants.LOGV) {
                Log.v(TAG, "download completed for " + mInfo.mUri);
            }
            finalizeDestinationFile(state);
            finalStatus = Downloads.Impl.STATUS_SUCCESS;
        } catch (StopRequest error) {
            // remove the cause before printing, in case it contains PII
            Log.w(TAG,
                    "Aborting request for download " + mInfo.mId + ": " + error.getMessage());
            finalStatus = error.mFinalStatus;
            // fall through to finally block
        } catch (Throwable ex) { //sometimes the socket code throws unchecked exceptions
            Log.w(TAG, "Exception for id " + mInfo.mId + ": " + ex);
            finalStatus = Downloads.Impl.STATUS_UNKNOWN_ERROR;
            // falls through to the code that reports an error
        } finally {
            if (wakeLock != null) {
                wakeLock.release();
                wakeLock = null;
            }

            cleanupDestination(state, finalStatus);
            notifyDownloadCompleted(finalStatus, state.mCountRetry, state.mRetryAfter,
                                    state.mRedirectCount, state.mGotData, state.mFilename,
                                    state.mNewUri, state.mMimeType);
            mInfo.mHasActiveThread = false;
        }
    }

    /**
     * Fully execute a single download request - setup and send the request, handle the response,
     * and transfer the data to the destination file.
     */
    private void executeDownload(State state, InnerState innerState, Call call)
            throws StopRequest, RetryDownload {


        Response response = sendRequest(state, call);
        handleExceptionalStatus(state, innerState, response);

        if (Constants.LOGV) {
            Log.v(TAG, "received response for " + mInfo.mUri);
        }

        processResponseHeaders(state, innerState, response);
        transferData(state, innerState, response);
    }

    /**
     * Check if current connectivity is valid for this request.
     */
    private void checkConnectivity(State state) throws StopRequest {
        int networkUsable = mInfo.checkCanUseNetwork();
        if (networkUsable != DownloadInfo.NETWORK_OK) {
            int status = Downloads.Impl.STATUS_WAITING_FOR_NETWORK;
            if (networkUsable == DownloadInfo.NETWORK_UNUSABLE_DUE_TO_SIZE) {
                status = Downloads.Impl.STATUS_QUEUED_FOR_WIFI;
                mInfo.notifyPauseDueToSize(true);
            } else if (networkUsable == DownloadInfo.NETWORK_RECOMMENDED_UNUSABLE_DUE_TO_SIZE) {
                status = Downloads.Impl.STATUS_QUEUED_FOR_WIFI;
                mInfo.notifyPauseDueToSize(false);
            }
            throw new StopRequest(status, mInfo.getLogMessageForNetworkError(networkUsable));
        }
    }

    /**
     * Transfer as much data as possible from the HTTP response to the destination file.
     * @param response stream for reading the HTTP response entity
     */
    private void transferData(State state, InnerState innerState, Response response) throws StopRequest {
        //entityStream = new BufferedInputStream(entityStream);
        InputStream entityStream = response.body().byteStream();
        byte data[] = new byte[Constants.BUFFER_SIZE];
        for (;;) {
            int bytesRead;
            try {
              bytesRead =  entityStream.read(data);
            } catch (IOException ex) {
                logNetworkState();
                ContentValues values = new ContentValues();
                values.put(Downloads.Impl.COLUMN_CURRENT_BYTES, innerState.mBytesSoFar);
                mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
                if (cannotResume(innerState)) {
                    String message = "while reading response: " + ex.toString()
                            + ", can't resume interrupted download with no ETag";
                    throw new StopRequest(Downloads.Impl.STATUS_CANNOT_RESUME,
                            message, ex);
                } else {
                    throw new StopRequest(getFinalStatusForHttpError(state),
                            "while reading response: " + ex.toString(), ex);
                }
            }
            if (bytesRead == -1) { // success, end of stream already reached
                handleEndOfStream(state, innerState);
                return;
            }

            state.mGotData = true;
            writeDataToDestination(state, data, bytesRead);
            innerState.mBytesSoFar += bytesRead;
            reportProgress(state, innerState);

            if (Constants.LOGVV) {
                Log.v(TAG, "downloaded " + innerState.mBytesSoFar + " for "
                      + mInfo.mUri);
            }

            checkPausedOrCanceled(state);
        }
    }

    /**
     * Called after a successful completion to take any necessary action on the downloaded file.
     */
    private void finalizeDestinationFile(State state) throws StopRequest {
        // make sure the file is readable
        //FileUtils.setPermissions(state.mFilename, 0644, -1, -1);

        try {
            Class fileUtilsCls = mContext.getClassLoader().loadClass("android.os.FileUtils");
            Method method = fileUtilsCls.getDeclaredMethod("setPermissions", String.class, int.class, int.class, int.class);
            method.invoke(null, state.mFilename, 0644, -1, -1);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        syncDestination(state);
    }

    /**
     * Called just before the thread finishes, regardless of status, to take any necessary action on
     * the downloaded file.
     */
    private void cleanupDestination(State state, int finalStatus) {
        closeDestination(state);
        if (state.mFilename != null && Downloads.Impl.isStatusError(finalStatus)) {
            new File(state.mFilename).delete();
            state.mFilename = null;
        }
    }

    /**
     * Sync the destination file to storage.
     */
    private void syncDestination(State state) {
        FileOutputStream downloadedFileStream = null;
        try {
            downloadedFileStream = new FileOutputStream(state.mFilename, true);
            downloadedFileStream.getFD().sync();
        } catch (FileNotFoundException ex) {
            Log.w(TAG, "file " + state.mFilename + " not found: " + ex);
        } catch (SyncFailedException ex) {
            Log.w(TAG, "file " + state.mFilename + " sync failed: " + ex);
        } catch (IOException ex) {
            Log.w(TAG, "IOException trying to sync " + state.mFilename + ": " + ex);
        } catch (RuntimeException ex) {
            Log.w(TAG, "exception while syncing file: ", ex);
        } finally {
            if(downloadedFileStream != null) {
                try {
                    downloadedFileStream.close();
                } catch (IOException ex) {
                    Log.w(TAG, "IOException while closing synced file: ", ex);
                } catch (RuntimeException ex) {
                    Log.w(TAG, "exception while closing file: ", ex);
                }
            }
        }
    }

    /**
     * @return true if the current download is a DRM file
     */
    private boolean isDrmFile(State state) {
        return Constants.MIMETYPE_DRM_MESSAGE.equalsIgnoreCase(state.mMimeType);
    }

    /**
     * Close the destination output stream.
     */
    private void closeDestination(State state) {
        try {
            // close the file
            if (state.mStream != null) {
                state.mStream.close();
                state.mStream = null;
            }
        } catch (IOException ex) {
            if (Constants.LOGV) {
                Log.v(TAG, "exception when closing the file after download : " + ex);
            }
            // nothing can really be done if the file can't be closed
        }
    }

    /**
     * Check if the download has been paused or canceled, stopping the request appropriately if it
     * has been.
     */
    private void checkPausedOrCanceled(State state) throws StopRequest {
        synchronized (mInfo) {
            if (mInfo.mControl == Downloads.Impl.CONTROL_PAUSED) {
                throw new StopRequest(Downloads.Impl.STATUS_PAUSED_BY_APP,
                        "download paused by owner");
            }
        }
        if (mInfo.mStatus == Downloads.Impl.STATUS_CANCELED) {
            throw new StopRequest(Downloads.Impl.STATUS_CANCELED, "download canceled");
        }
    }

    /**
     * Report download progress through the database if necessary.
     */
    private void reportProgress(State state, InnerState innerState) {
        long now = mSystemFacade.currentTimeMillis();
        if (innerState.mBytesSoFar - innerState.mBytesNotified
                        > Constants.MIN_PROGRESS_STEP
                && now - innerState.mTimeLastNotification
                        > Constants.MIN_PROGRESS_TIME) {
            ContentValues values = new ContentValues();
            values.put(Downloads.Impl.COLUMN_CURRENT_BYTES, innerState.mBytesSoFar);
            mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
            innerState.mBytesNotified = innerState.mBytesSoFar;
            innerState.mTimeLastNotification = now;
        }
    }

    /**
     * Write a data buffer to the destination file.
     * @param data buffer containing the data to write
     * @param bytesRead how many bytes to write from the buffer
     */
    private void writeDataToDestination(State state, byte[] data, int bytesRead)
            throws StopRequest {
        for (;;) {
            try {
                if (state.mStream == null) {
                    state.mStream = new FileOutputStream(state.mFilename, true);
                }
                state.mStream.write(data, 0, bytesRead);
                if (mInfo.mDestination == Downloads.Impl.DESTINATION_EXTERNAL
                            && !isDrmFile(state)) {
                    closeDestination(state);
                }
                return;
            } catch (IOException ex) {
                if (mInfo.isOnCache()) {
                    if (Helpers.discardPurgeableFiles(mContext, Constants.BUFFER_SIZE)) {
                        continue;
                    }
                } else if (!Helpers.isExternalMediaMounted()) {
                    throw new StopRequest(Downloads.Impl.STATUS_DEVICE_NOT_FOUND_ERROR,
                            "external media not mounted while writing destination file");
                }

                long availableBytes =
                    Helpers.getAvailableBytes(Helpers.getFilesystemRoot(state.mFilename));
                if (availableBytes < bytesRead) {
                    throw new StopRequest(Downloads.Impl.STATUS_INSUFFICIENT_SPACE_ERROR,
                            "insufficient space while writing destination file", ex);
                }
                throw new StopRequest(Downloads.Impl.STATUS_FILE_ERROR,
                        "while writing destination file: " + ex.toString(), ex);
            }
        }
    }

    /**
     * Called when we've reached the end of the HTTP response stream, to update the database and
     * check for consistency.
     */
    private void handleEndOfStream(State state, InnerState innerState) throws StopRequest {
        ContentValues values = new ContentValues();
        values.put(Downloads.Impl.COLUMN_CURRENT_BYTES, innerState.mBytesSoFar);
        if (innerState.mHeaderContentLength == null) {
            values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, innerState.mBytesSoFar);
        }
        mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);

        boolean lengthMismatched = (innerState.mHeaderContentLength != null)
                && (innerState.mBytesSoFar != Integer.parseInt(innerState.mHeaderContentLength));
        if (lengthMismatched) {
            if (cannotResume(innerState)) {
                throw new StopRequest(Downloads.Impl.STATUS_CANNOT_RESUME,
                        "mismatched content length");
            } else {
                throw new StopRequest(getFinalStatusForHttpError(state),
                        "closed socket before end of file");
            }
        }
    }

    private boolean cannotResume(InnerState innerState) {
        return innerState.mBytesSoFar > 0 && !mInfo.mNoIntegrity && innerState.mHeaderETag == null;
    }

    /**
     * Read some data from the HTTP response stream, handling I/O errors.
     * @param data buffer to use to read data
     * @param entityStream stream for reading the HTTP response entity
     * @return the number of bytes actually read or -1 if the end of the stream has been reached
     */
    private int readFromResponse(State state, InnerState innerState, byte[] data,
                                 InputStream entityStream) throws StopRequest {
        try {
            return entityStream.read(data);
        } catch (IOException ex) {
            logNetworkState();
            ContentValues values = new ContentValues();
            values.put(Downloads.Impl.COLUMN_CURRENT_BYTES, innerState.mBytesSoFar);
            mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
            if (cannotResume(innerState)) {
                String message = "while reading response: " + ex.toString()
                + ", can't resume interrupted download with no ETag";
                throw new StopRequest(Downloads.Impl.STATUS_CANNOT_RESUME,
                        message, ex);
            } else {
                throw new StopRequest(getFinalStatusForHttpError(state),
                        "while reading response: " + ex.toString(), ex);
            }
        }
    }


    private void logNetworkState() {
        if (Constants.LOGX) {
            Log.i(TAG,
                    "Net " + (Helpers.isNetworkAvailable(mSystemFacade) ? "Up" : "Down"));
        }
    }

    /**
     * Read HTTP response headers and take appropriate action, including setting up the destination
     * file and updating the database.
     */
    private void processResponseHeaders(State state, InnerState innerState, Response response)
            throws StopRequest {
        if (innerState.mContinuingDownload) {
            // ignore response headers on resume requests
            return;
        }

        readResponseHeaders(state, innerState, response);

        try {
            state.mFilename = Helpers.generateSaveFile(
                    mContext,
                    mInfo.mUri,
                    mInfo.mHint,
                    innerState.mHeaderContentDisposition,
                    innerState.mHeaderContentLocation,
                    state.mMimeType,
                    mInfo.mDestination,
                    (innerState.mHeaderContentLength != null) ?
                            Long.parseLong(innerState.mHeaderContentLength) : 0,
                    mInfo.mIsPublicApi);
        } catch (Helpers.GenerateSaveFileError exc) {
            throw new StopRequest(exc.mStatus, exc.mMessage);
        }
        try {
            state.mStream = new FileOutputStream(state.mFilename);
        } catch (FileNotFoundException exc) {
            throw new StopRequest(Downloads.Impl.STATUS_FILE_ERROR,
                    "while opening destination file: " + exc.toString(), exc);
        }
        if (Constants.LOGV) {
            Log.v(TAG, "writing " + mInfo.mUri + " to " + state.mFilename);
        }

        updateDatabaseFromHeaders(state, innerState);
        // check connectivity again now that we know the total size
        checkConnectivity(state);
    }

    /**
     * Update necessary database fields based on values of HTTP response headers that have been
     * read.
     */
    private void updateDatabaseFromHeaders(State state, InnerState innerState) {
        ContentValues values = new ContentValues();
        values.put(Downloads.Impl._DATA, state.mFilename);
        if (innerState.mHeaderETag != null) {
            values.put(Constants.ETAG, innerState.mHeaderETag);
        }
        if (state.mMimeType != null) {
            values.put(Downloads.Impl.COLUMN_MIME_TYPE, state.mMimeType);
        }
        values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, mInfo.mTotalBytes);
        mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
    }

    /**
     * Read headers from the HTTP response and store them into local state.
     */
    private void readResponseHeaders(State state, InnerState innerState, Response response)
            throws StopRequest {
        innerState.mHeaderContentDisposition = response.header("Content-Disposition");

        innerState.mHeaderContentLocation = response.header("Content-Location");

        if (state.mMimeType == null) {
            String mimeType  = response.header("Content-Type");
            if (!TextUtils.isEmpty(mimeType)) {
                state.mMimeType = sanitizeMimeType(mimeType);
            }
        }
        innerState.mHeaderETag = response.header("ETag");
        String headerTransferEncoding = response.header("Transfer-Encoding");
        if (headerTransferEncoding == null) {
            innerState.mHeaderContentLength = response.header("Content-Length");
            mInfo.mTotalBytes = Long.parseLong(innerState.mHeaderContentLength);
        } else {
            // Ignore content-length with transfer-encoding - 2616 4.4 3
            if (Constants.LOGVV) {
                Log.v(TAG,
                        "ignoring content-length because of xfer-encoding");
            }
        }
        if (Constants.LOGVV) {
            Log.v(TAG, "Content-Disposition: " +
                    innerState.mHeaderContentDisposition);
            Log.v(TAG, "Content-Length: " + innerState.mHeaderContentLength);
            Log.v(TAG, "Content-Location: " + innerState.mHeaderContentLocation);
            Log.v(TAG, "Content-Type: " + state.mMimeType);
            Log.v(TAG, "ETag: " + innerState.mHeaderETag);
            Log.v(TAG, "Transfer-Encoding: " + headerTransferEncoding);
        }

        boolean noSizeInfo = innerState.mHeaderContentLength == null
                && (headerTransferEncoding == null
                    || !headerTransferEncoding.equalsIgnoreCase("chunked"));
        if (!mInfo.mNoIntegrity && noSizeInfo) {
            throw new StopRequest(Downloads.Impl.STATUS_HTTP_DATA_ERROR,
                    "can't know size of download, giving up");
        }
    }

    /**
     * Check the HTTP response status and handle anything unusual (e.g. not 200/206).
     */
    private void handleExceptionalStatus(State state, InnerState innerState, Response response)
            throws StopRequest, RetryDownload {
        int statusCode = response.code();
        if (statusCode == 503 && mInfo.mNumFailed < Constants.MAX_RETRIES) {
            handleServiceUnavailable(state, response);
        }
        if (statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307) {
            handleRedirect(state, response, statusCode);
        }

        int expectedStatus = innerState.mContinuingDownload ? 206 : Downloads.Impl.STATUS_SUCCESS;
        if (statusCode != expectedStatus) {
            handleOtherStatus(state, innerState, statusCode);
        }
    }

    /**
     * Handle a status that we don't know how to deal with properly.
     */
    private void handleOtherStatus(State state, InnerState innerState, int statusCode)
            throws StopRequest {
        int finalStatus;
        if (Downloads.Impl.isStatusError(statusCode)) {
            finalStatus = statusCode;
        } else if (statusCode >= 300 && statusCode < 400) {
            finalStatus = Downloads.Impl.STATUS_UNHANDLED_REDIRECT;
        } else if (innerState.mContinuingDownload && statusCode == Downloads.Impl.STATUS_SUCCESS) {
            finalStatus = Downloads.Impl.STATUS_CANNOT_RESUME;
        } else {
            finalStatus = Downloads.Impl.STATUS_UNHANDLED_HTTP_CODE;
        }
        throw new StopRequest(finalStatus, "http error " + statusCode);
    }

    /**
     * Handle a 3xx redirect status.
     */
    private void handleRedirect(State state, Response response, int statusCode)
            throws StopRequest, RetryDownload {
        if (Constants.LOGVV) {
            Log.v(TAG, "got HTTP redirect " + statusCode);
        }
        if (state.mRedirectCount >= Constants.MAX_REDIRECTS) {
            throw new StopRequest(Downloads.Impl.STATUS_TOO_MANY_REDIRECTS, "too many redirects");
        }
        String location = response.header("Location");
        if (TextUtils.isEmpty(location)) {
            return;
        }
        if (Constants.LOGVV) {
            Log.v(TAG, "Location :" + location);
        }

        String newUri;
        try {
            newUri = new URI(mInfo.mUri).resolve(new URI(location)).toString();
        } catch(URISyntaxException ex) {
            if (Constants.LOGV) {
                Log.d(TAG, "Couldn't resolve redirect URI " + location+ " for " + mInfo.mUri);
            }
            throw new StopRequest(Downloads.Impl.STATUS_HTTP_DATA_ERROR,
                    "Couldn't resolve redirect URI");
        }
        ++state.mRedirectCount;
        state.mRequestUri = newUri;
        if (statusCode == 301 || statusCode == 303) {
            // use the new URI for all future requests (should a retry/resume be necessary)
            state.mNewUri = newUri;
        }
        throw new RetryDownload();
    }

    /**
     * Handle a 503 Service Unavailable status by processing the Retry-After header.
     */
    private void handleServiceUnavailable(State state, Response response) throws StopRequest {
        if (Constants.LOGVV) {
            Log.v(TAG, "got HTTP response code 503");
        }
        state.mCountRetry = true;
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null) {
           try {
               if (Constants.LOGVV) {
                   Log.v(TAG, "Retry-After :" + retryAfter);
               }
               state.mRetryAfter = Integer.parseInt(retryAfter);
               if (state.mRetryAfter < 0) {
                   state.mRetryAfter = 0;
               } else {
                   if (state.mRetryAfter < Constants.MIN_RETRY_AFTER) {
                       state.mRetryAfter = Constants.MIN_RETRY_AFTER;
                   } else if (state.mRetryAfter > Constants.MAX_RETRY_AFTER) {
                       state.mRetryAfter = Constants.MAX_RETRY_AFTER;
                   }
                   state.mRetryAfter += Helpers.sRandom.nextInt(Constants.MIN_RETRY_AFTER + 1);
                   state.mRetryAfter *= 1000;
               }
           } catch (NumberFormatException ex) {
               // ignored - retryAfter stays 0 in this case.
           }
        }
        throw new StopRequest(Downloads.Impl.STATUS_WAITING_TO_RETRY,
                "got 503 Service Unavailable, will retry later");
    }

    /**
     * Send the request to the server, handling any I/O exceptions.
     */
    private Response sendRequest(State state, Call call)
            throws StopRequest {
        try {
            return call.execute();
        } catch (IllegalArgumentException ex) {
            throw new StopRequest(Downloads.Impl.STATUS_HTTP_DATA_ERROR,
                    "while trying to execute request: " + ex.toString(), ex);
        } catch (IOException ex) {
            logNetworkState();
            throw new StopRequest(getFinalStatusForHttpError(state),
                    "while trying to execute request: " + ex.toString(), ex);
        }
    }

    private int getFinalStatusForHttpError(State state) {
        if (!Helpers.isNetworkAvailable(mSystemFacade)) {
            return Downloads.Impl.STATUS_WAITING_FOR_NETWORK;
        } else if (mInfo.mNumFailed < Constants.MAX_RETRIES) {
            state.mCountRetry = true;
            return Downloads.Impl.STATUS_WAITING_TO_RETRY;
        } else {
            Log.w(TAG, "reached max retries for " + mInfo.mId);
            return Downloads.Impl.STATUS_HTTP_DATA_ERROR;
        }
    }

    /**
     * Prepare the destination file to receive data.  If the file already exists, we'll set up
     * appropriately for resumption.
     */
    private void setupDestinationFile(State state, InnerState innerState)
            throws StopRequest {
        if (state.mFilename != null) { // only true if we've already run a thread for this download
            if (!Helpers.isFilenameValid(state.mFilename)) {
                // this should never happen
                throw new StopRequest(Downloads.Impl.STATUS_FILE_ERROR,
                        "found invalid internal destination filename");
            }
            // We're resuming a download that got interrupted
            File f = new File(state.mFilename);
            if (f.exists()) {
                long fileLength = f.length();
                if (fileLength == 0) {
                    // The download hadn't actually started, we can restart from scratch
                    f.delete();
                    state.mFilename = null;
                } else if (mInfo.mETag == null && !mInfo.mNoIntegrity) {
                    // This should've been caught upon failure
                    f.delete();
                    throw new StopRequest(Downloads.Impl.STATUS_CANNOT_RESUME,
                            "Trying to resume a download that can't be resumed");
                } else {
                    // All right, we'll be able to resume this download
                    try {
                        state.mStream = new FileOutputStream(state.mFilename, true);
                    } catch (FileNotFoundException exc) {
                        throw new StopRequest(Downloads.Impl.STATUS_FILE_ERROR,
                                "while opening destination for resuming: " + exc.toString(), exc);
                    }
                    innerState.mBytesSoFar = (int) fileLength;
                    if (mInfo.mTotalBytes != -1) {
                        innerState.mHeaderContentLength = Long.toString(mInfo.mTotalBytes);
                    }
                    innerState.mHeaderETag = mInfo.mETag;
                    innerState.mContinuingDownload = true;
                }
            }
        }

        if (state.mStream != null && mInfo.mDestination == Downloads.Impl.DESTINATION_EXTERNAL
                && !isDrmFile(state)) {
            closeDestination(state);
        }
    }

    /**
     * Add custom headers for this download to the HTTP request.
     */
    private void addRequestHeaders(InnerState innerState, Request.Builder builder) {
        for (Pair<String, String> header : mInfo.getHeaders()) {
            builder.addHeader(header.first, header.second);

        }

        if (innerState.mContinuingDownload) {
            if (innerState.mHeaderETag != null) {
                builder.addHeader("If-Match", innerState.mHeaderETag);
            }
            builder.addHeader("Range", "bytes=" + innerState.mBytesSoFar + "-");
        }
    }

    /**
     * Stores information about the completed download, and notifies the initiating application.
     */
    private void notifyDownloadCompleted(
            int status, boolean countRetry, int retryAfter, int redirectCount, boolean gotData,
            String filename, String uri, String mimeType) {
        Log.i(TAG, "notifyDownlaodCompleted");
        notifyThroughDatabase(
                status, countRetry, retryAfter, redirectCount, gotData, filename, uri, mimeType);
        if (Downloads.Impl.isStatusCompleted(status)) {
            mInfo.sendIntentIfRequested();
        }
    }

    private void notifyThroughDatabase(
            int status, boolean countRetry, int retryAfter, int redirectCount, boolean gotData,
            String filename, String uri, String mimeType) {
        ContentValues values = new ContentValues();
        values.put(Downloads.Impl.COLUMN_STATUS, status);
        values.put(Downloads.Impl._DATA, filename);
        if (uri != null) {
            values.put(Downloads.Impl.COLUMN_URI, uri);
        }
        values.put(Downloads.Impl.COLUMN_MIME_TYPE, mimeType);
        values.put(Downloads.Impl.COLUMN_LAST_MODIFICATION, mSystemFacade.currentTimeMillis());
        values.put(Constants.RETRY_AFTER_X_REDIRECT_COUNT, retryAfter + (redirectCount << 28));
        if (!countRetry) {
            values.put(Constants.FAILED_CONNECTIONS, 0);
        } else if (gotData) {
            values.put(Constants.FAILED_CONNECTIONS, 1);
        } else {
            values.put(Constants.FAILED_CONNECTIONS, mInfo.mNumFailed + 1);
        }

        mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
    }

    /**
     * Clean up a mimeType string so it can be used to dispatch an intent to
     * view a downloaded asset.
     * @param mimeType either null or one or more mime types (semi colon separated).
     * @return null if mimeType was null. Otherwise a string which represents a
     * single mimetype in lowercase and with surrounding whitespaces trimmed.
     */
    private static String sanitizeMimeType(String mimeType) {
        try {
            mimeType = mimeType.trim().toLowerCase(Locale.ENGLISH);

            final int semicolonIndex = mimeType.indexOf(';');
            if (semicolonIndex != -1) {
                mimeType = mimeType.substring(0, semicolonIndex);
            }
            return mimeType;
        } catch (NullPointerException npe) {
            return null;
        }
    }
}

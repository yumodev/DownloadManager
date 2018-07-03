package com.yumodev.download;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import com.ymdev.download.providers.DownloadManager;
import com.yumo.common.io.YmAdFileUtil;
import com.yumo.common.io.YmFileUtil;
import com.yumo.common.log.Log;
import com.yumo.demo.view.YmTestFragment;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.ymdev.download.providers.Downloads.COLUMN_STATUS;
import static com.ymdev.download.providers.Downloads.isStatusClientError;

/**
 * Created by yumo on 2018/6/28.
 */

public class DownloadLibTest extends YmTestFragment {
    private final String LOG_TAG = Log.LIB_TAG;

    private static final String DOWNLOAD_MANAGER_PACKAGE_NAME = "com.android.providers.downloads";

    private long mDownloadId = 0L;
    //private String mApkUrl = "https://qd.myapp.com/myapp/qqteam/AndroidQQ/mobileqq_android.apk";
    private String mApkUrl = "https://pro-app-qn.fir.im/c8582d2daa700aac7cb0762c5833e8c6866e651c.apk?attname=app-release.apk_1.0.1.apk&e=1530102591&token=LOvmia8oXF4xnLh0IdH05XMYpH6ENHNpARlmPc-T:jUmGVC1RGtKBBqxN0zUOAmmZUDw=";
    //private String mApkUrl = "http://pic32.nipic.com/20130823/12976223_141018174311_2.jpg";
    private String mDirName = Environment.getDownloadCacheDirectory().getAbsolutePath();

    private DownloadManager mDownloadManager = null;
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDownloadManager = new DownloadManager(getActivity().getApplicationContext().getContentResolver(), getActivity().getPackageName());
    }

    @Override
    public void onResume() {
        super.onResume();
    }


    public void testDownloadDir(){
        String dir = Environment.getExternalStorageDirectory()+File.separator+getTestDownloadDir();
        YmFileUtil.createDirectory(dir);
        showToastMessage(getTestDownloadDir());
    }

    private String getTestDownloadDir(){
        return getContext().getPackageName()+File.separator+"download";
    }
    /**
     * 测试一个最简单的下载功能
     */
    public void testDownload(){
        download(getContext(), mApkUrl, getActivity().getExternalCacheDir().getPath(), YmFileUtil.getFileNameFromPath(mApkUrl));
    }

    private void download(Context context, String url, String dirName, String fileName){
        //获取DownloadManager对象
        //创建下载Request请求
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        //设置下载后保持的文件路径和名称，必须设置
        request.setDestinationInExternalPublicDir(dirName, fileName);
        //开启下载文件，返回一个id，可以通过该Id插件下载的数据和信息
        mDownloadId = mDownloadManager.enqueue(request);
    }

    public void testPauseDownload(){

    }

    /**
     * 测试一个最简单的下载功能
     */
    public void testDownload1(){
        String dirName = getTestDownloadDir();
        String fileName =(Uri.parse(mApkUrl)).getLastPathSegment();
        downloadApk(getContext(), mApkUrl, dirName, fileName);
    }

    public void testOpenManager(){
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + DOWNLOAD_MANAGER_PACKAGE_NAME));
        getContext().startActivity(intent);

    }

    public void testRetryDownlaod(){
        mDownloadManager.restartDownload(mDownloadId);
    }

    /**
     * Cancel downloads and remove them from the download manager.  Each download will be stopped if
     * it was running, and it will no longer be accessible through the download manager.
     * If there is a downloaded file, partial or complete, it is deleted.
     *
     */
    public void testCancelDownload(){
        mDownloadManager.remove(mDownloadId);
    }

    /**
     * setDestinationInExternalFilesDir(Context context, String dirType, String subPath)
     * setDestinationInExternalPublicDir(String dirType, String subPath)
     */
    public void testSetPath(){

    }

    public void testPrintAllDownload(){
        printAllDownload();
    }

    private void printAllDownload(){
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL|DownloadManager.STATUS_FAILED|DownloadManager.STATUS_PAUSED|DownloadManager.STATUS_RUNNING|DownloadManager.STATUS_PENDING);
        //DownloadManager downloadManager = (DownloadManager)getContext().getSystemService(Context.DOWNLOAD_SERVICE);

        Cursor c = mDownloadManager.query(query);
        while (c.moveToNext()){
            Download download = new Download();
            download.id = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_ID));
            download.title = c.getString(c.getColumnIndex(DownloadManager.COLUMN_TITLE));
            download.description = c.getString(c.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION));
            download.url = c.getString(c.getColumnIndex(DownloadManager.COLUMN_URI));
            download.status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
            download.totalBytesSize = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            download.bytesDownloadSoFar = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            download.mediaType = c.getString(c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE));
            download.mediappoviderUrl = c.getString(c.getColumnIndex(DownloadManager.COLUMN_MEDIAPROVIDER_URI));
            download.localFileName = getLocalFilePath(c);
            download.reason = c.getString(c.getColumnIndex(DownloadManager.COLUMN_REASON));
            download.lastModefiedTime = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));
            Log.i(Log.LIB_TAG, download.toString());
        }
        c.close();
    }

    public void testGetDownloadIdFromUrl(){
        List<Download> downloads = getDownloadIdFromUrl(mApkUrl);
        for (Download download : downloads){
            if (download.status != DownloadManager.STATUS_SUCCESSFUL && download.status != DownloadManager.STATUS_FAILED){
                mDownloadManager.restartDownload(download.id);
            }
        }
    }

    private List<Download> getDownloadIdFromUrl(String url){
        DownloadManager.Query query = new DownloadManager.Query();
        List<Download> list = new ArrayList<>();
        Cursor c = mDownloadManager.query(query);
        while (c.moveToNext()){
            Download download = new Download();
            download.url = c.getString(c.getColumnIndex(DownloadManager.COLUMN_URI));
            showToastMessage(c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)));
            if (!url.equals(url)){
                continue;
            }
            download.status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
            if (download.status == DownloadManager.STATUS_FAILED){
                mDownloadManager.remove(download.id);
                continue;
            }
            download.id = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_ID));
            download.title = c.getString(c.getColumnIndex(DownloadManager.COLUMN_TITLE));
            download.description = c.getString(c.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION));


            download.totalBytesSize = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            download.bytesDownloadSoFar = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            download.mediaType = c.getString(c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE));
            download.mediappoviderUrl = c.getString(c.getColumnIndex(DownloadManager.COLUMN_MEDIAPROVIDER_URI));
            download.localFileName = getLocalFilePath(c);
            download.reason = c.getString(c.getColumnIndex(DownloadManager.COLUMN_REASON));
            download.lastModefiedTime = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));
            Log.i(Log.LIB_TAG, download.toString());
            list.add(download);
        }
        c.close();

        return list;
    }


    public void testCheckStatus(){
        checkStatus(mDownloadId);
    }

    private void checkStatus(long taskId){
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(taskId);
        Cursor c = mDownloadManager.query(query);
        if(c.moveToFirst()){
            int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
            int fileUriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            String fileUri = c.getString(fileUriIdx);
            String fileName = null;
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
                if (fileUri != null) {
                    fileName = Uri.parse(fileUri).getPath();
                }
            } else {
                //Android 7.0以上的方式：请求获取写入权限，这一步报错
                //过时的方式：DownloadManager.COLUMN_LOCAL_FILENAME
//                int fileNameIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME);
//                fileName = c.getString(fileNameIdx);
            }
            Log.i(Log.LIB_TAG, "fileName:"+fileName+" "+status);

        }
        c.close();
    }

    public void testCheckStatusByUri(){
        DownloadManager.Query query = new DownloadManager.Query();
        Cursor c = mDownloadManager.query(query);
        if(c.moveToFirst()){
            int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
            int fileUriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            String fileUri = c.getString(fileUriIdx);
            String fileName = null;
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
                if (fileUri != null) {
                    fileName = Uri.parse(fileUri).getPath();
                }
            } else {
                //Android 7.0以上的方式：请求获取写入权限，这一步报错
                //过时的方式：DownloadManager.COLUMN_LOCAL_FILENAME
//                int fileNameIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME);
//                fileName = c.getString(fileNameIdx);
            }
            Log.i(Log.LIB_TAG, "fileName:"+fileName+" "+status);

        }
        c.close();
    }

    private String getLocalFilePath(Cursor c){
        int fileUriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
        String fileUri = c.getString(fileUriIdx);
        String fileName = null;
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            if (fileUri != null) {
                fileName = Uri.parse(fileUri).getPath();
            }
        } else {
            //Android 7.0以上的方式：请求获取写入权限，这一步报错
            //过时的方式：DownloadManager.COLUMN_LOCAL_FILENAME
            int fileNameIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            fileName = c.getString(fileNameIdx);
        }
        return fileName;
    }

    private void downloadApk(Context context, String url, String path, String name){
        //创建下载任务
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        //移动网络情况下是否允许漫游
        request.setAllowedOverRoaming(true);

        //在通知栏中显示，默认就是显示的
        request.setTitle(name);
        request.setDescription("Apk Downloading");
        //request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        request.setVisibleInDownloadsUi(true);

        //设置显示的Mime
        MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
        request.setMimeType(mimeTypeMap.getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url)));

        //设置下载的路径
        request.setDestinationInExternalPublicDir(path , name);

        //获取DownloadManager
        //将下载请求加入下载队列，加入下载队列后会给该任务返回一个long型的id，通过该id可以取消任务，重启任务、获取下载的文件等等
        mDownloadManager.enqueue(request);

        //注册广播接收者，监听下载状态
        context.registerReceiver(receiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        context.registerReceiver(receiver,
                new IntentFilter(DownloadManager.ACTION_NOTIFICATION_CLICKED));
        context.registerReceiver(receiver,
                new IntentFilter(DownloadManager.ACTION_VIEW_DOWNLOADS));

    }

    //广播监听下载的各个状态
    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            checkStatus(getContext(), intent);
        }
    };

    //检查下载状态
    private void checkStatus(Context context, Intent intent) {
        DownloadManager.Query query = new DownloadManager.Query();
        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        //通过下载的id查找
        query.setFilterById(downloadId);
        Cursor c = mDownloadManager.query(query);
        if (c.moveToFirst()) {
            int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
            String filename = getLocalFilePath(c);
            switch (status) {
                //下载暂停
                case DownloadManager.STATUS_PAUSED:
                    Log.i(Log.LIB_TAG, "下载暂停");
                    break;
                //下载延迟
                case DownloadManager.STATUS_PENDING:
                    Log.i(Log.LIB_TAG, "下载延迟");
                    break;
                //正在下载
                case DownloadManager.STATUS_RUNNING:
                    Log.i(Log.LIB_TAG, "下载中");
                    break;
                //下载完成
                case DownloadManager.STATUS_SUCCESSFUL:
                    Log.i(Log.LIB_TAG, "下载完成");
                    //下载完成安装APK
                    Toast.makeText(context, filename, Toast.LENGTH_SHORT).show();
                    installAPK(context, downloadId);
                    break;
                case DownloadManager.STATUS_FAILED:
                    Log.i(Log.LIB_TAG, "下载失败");
                    break;
            }
        }
        c.close();
    }

    //下载到本地后执行安装
    private void installAPK(Context context, long downloadId) {
        //获取下载文件的Uri
        Uri downloadFileUri = getUriForDownloadedFile(downloadId);
        if (downloadFileUri != null) {
            Intent intent= new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(downloadFileUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            context.unregisterReceiver(receiver);
        }
    }

    /**
     * Returns the {@link Uri} of the given downloaded file id, if the file is
     * downloaded successfully. Otherwise, null is returned.
     *
     * @param id the id of the downloaded file.
     * @return the {@link Uri} of the given downloaded file id, if download was
     *         successful. null otherwise.
     */
    public Uri getUriForDownloadedFile(long id) {
        // to check if the file is in cache, get its destination from the database
       DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        Cursor cursor = null;
        try {
            cursor = mDownloadManager.query(query);
            if (cursor == null) {
                return null;
            }
            if (cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_STATUS));
                String filePath = getLocalFilePath(cursor);
                if (android.app.DownloadManager.STATUS_SUCCESSFUL == status) {
                    //return ContentUris.withAppendedId(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, id);
                    int fileUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                    String fileUri = cursor.getString(fileUriIdx);
                    return Uri.parse(fileUri);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        // downloaded file not found or its status is not 'successfully completed'
        return null;
    }


    public void testDownloadAndApk(){
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String fileName = YmAdFileUtil.getFileCachePath(getContext()) + File.separator + "test.apk";
                downFile(mApkUrl, fileName);
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        installAPK(fileName);
                    }
                });

            }
        }).start();
    }


    //下载到本地后执行安装
    private void installAPK(String fileName) {
        Intent intent= new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(new File(fileName)),  "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * 下载一个文件，注意使用的时候需要放到一个线程里面。
     * yumo
     * @param url      文件源路径
     * @param fileName 文件下载后要保存的路径
     * @return int 大于0 表示返回的文件的大小；－1:参数不对；－3:表示网络不对；－2:文件路径不对
     * 2015-1-15
     */
    public long downFile(String url, String fileName) {
        //传入参数失败
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(fileName)) {
            return 0;
        }

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();

        try {
            Response response = client.newCall(request).execute();
            if (response != null && response.isSuccessful()) {
                FileOutputStream fileStream = new FileOutputStream(fileName);
                InputStream is = response.body().byteStream();
                BufferedInputStream bis = new BufferedInputStream(is);

                int len = 0;
                byte[] buffer = new byte[1024];

                while ((len = bis.read(buffer)) != -1) {
                    fileStream.write(buffer, 0, len);
                }

                fileStream.close();
                bis.close();
                is.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return (new File(fileName)).length();
    }

    public static class Download{
        public long id;
        public String title;
        public String description;
        public String url;
        public int status;
        public String mediaType;
        public long totalBytesSize;
        public String reason;
        public long bytesDownloadSoFar;
        public long lastModefiedTime;
        public String mediappoviderUrl;
        public String allowWrite;
        public String localFileName;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("id:"+id);
            sb.append("\ntitle:"+title);
            sb.append("\ndescription:"+description);
            sb.append("\nurl:"+url);
            sb.append("\nstatus:"+status);
            sb.append("\nmediaType:"+mediaType);
            sb.append("\ntotalBytesSize:"+totalBytesSize);
            sb.append("\nreason:"+reason);
            sb.append("\nbytesDownloadSoFar:"+bytesDownloadSoFar);
            sb.append("\nlastModefiedTime:"+lastModefiedTime);
            sb.append("\nmediappoviderUrl:"+mediappoviderUrl);
            sb.append("\nallowWrite:"+allowWrite);
            sb.append("\nlocalFileName:"+localFileName);
            return sb.toString();

        }
    }
}

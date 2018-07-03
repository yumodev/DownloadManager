package com.yumodev.download;

import com.yumo.demo.entry.YmPackageInfo;
import com.yumo.demo.listener.IGetPackageData;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by yumo on 2018/7/2.
 */

public class TestPacage implements IGetPackageData {
    @Override
    public List<YmPackageInfo> getPackageList() {
        List<YmPackageInfo> packageInfos = new ArrayList<>();
        packageInfos.add(new YmPackageInfo("downlaod", "yumodev.DownloadListTest"));
        return null;
    }
}

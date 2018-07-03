package com.yumodev.download;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

import com.yumo.demo.config.Config;
import com.yumo.demo.view.YmTestClassFragment;
import com.yumo.demo.view.YmTestPackageFragment;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        showTestPackageHomePage();
    }

    private void showTestPackageHomePage(){
        Bundle bundle = new Bundle();
        bundle.putString("packageName", getPackageName());
        bundle.putInt(Config.ARGUMENT_TOOLBAR_VISIBLE, View.GONE);
        YmTestClassFragment testClassFragment = new YmTestClassFragment();
        testClassFragment.setArguments(bundle);
        getSupportFragmentManager().beginTransaction().replace(R.id.test_fragment_id, testClassFragment).commit();
    }
}

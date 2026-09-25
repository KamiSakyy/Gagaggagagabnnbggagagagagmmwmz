package com.vortex.vpn.ui;

import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.widget.Toast;

import com.vortex.vpn.BuildConfig;
import com.vortex.vpn.R;
import com.vortex.vpn.core.EngineSelfTest;

import io.nekohasekai.libbox.Libbox;

/** Version and engine information. */
public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_about);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        TextView app = findViewById(R.id.text_app_version);
        TextView engine = findViewById(R.id.text_engine_version);
        TextView device = findViewById(R.id.text_device);

        app.setText(getString(R.string.about_app_version, BuildConfig.VERSION_NAME,
                String.valueOf(BuildConfig.VERSION_CODE)));
        String version = "?";
        try {
            version = Libbox.version();
        } catch (Throwable ignored) {
        }
        engine.setText(getString(R.string.about_engine_version, version));
        device.setText(getString(R.string.about_device, Build.MANUFACTURER, Build.MODEL,
                Build.VERSION.RELEASE, String.valueOf(Build.VERSION.SDK_INT), Build.SUPPORTED_ABIS[0]));

        findViewById(R.id.card_engine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSelfTest();
            }
        });
    }

    /** Starts the on-device engine check and reports the verdict in a dialog. */
    private void runSelfTest() {
        final TextView hint = findViewById(R.id.text_self_test);
        hint.setText(R.string.about_self_test_running);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = EngineSelfTest.run();
                EngineSelfTest.logResult(result, "about");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hint.setText(R.string.about_self_test_hint);
                        new AlertDialog.Builder(AboutActivity.this)
                                .setTitle(R.string.about_self_test)
                                .setMessage(result)
                                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        dialog.dismiss();
                                    }
                                })
                                .show();
                    }
                });
            }
        }, "engine-self-test").start();
        Toast.makeText(this, R.string.about_self_test_running, Toast.LENGTH_SHORT).show();
    }
}

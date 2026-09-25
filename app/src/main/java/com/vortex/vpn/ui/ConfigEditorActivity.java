package com.vortex.vpn.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.vortex.vpn.Prefs;
import com.vortex.vpn.R;
import com.vortex.vpn.core.ScreenAudit;
import com.vortex.vpn.core.VpnServiceVortex;
import com.vortex.vpn.db.Repo;
import com.vortex.vpn.model.Subscription;
import com.vortex.vpn.sub.SubImporter;

import io.nekohasekai.libbox.Libbox;

/** Raw sing-box configuration editor with validation and formatting. */
public class ConfigEditorActivity extends AppCompatActivity {

    public static final String EXTRA_SUBSCRIPTION_ID = "subscription_id";

    private EditText editor;
    private TextView status;
    private long subscriptionId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config_editor);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_config);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        editor = findViewById(R.id.editor);
        status = findViewById(R.id.text_result);
        subscriptionId = getIntent().getLongExtra(EXTRA_SUBSCRIPTION_ID, 0);

        if (subscriptionId > 0) {
            Subscription subscription = Repo.subscription(this, subscriptionId);
            if (subscription != null && subscription.rawConfig != null) {
                editor.setText(subscription.rawConfig);
            }
            status.setText(getString(R.string.config_editing, subscription == null ? "" : subscription.name));
        } else {
            editor.setHint(R.string.config_hint);
        }
        check(true);
        ScreenAudit.handOff(this);
    }

    private String currentConfig() {
        return editor.getText().toString();
    }

    private void check(boolean quiet) {
        String config = currentConfig().trim();
        if (config.isEmpty()) {
            if (!quiet) {
                toast(getString(R.string.config_empty));
            }
            return;
        }
        try {
            Libbox.checkConfig(config);
            status.setText(R.string.config_valid);
            if (!quiet) {
                toast(getString(R.string.config_valid));
            }
        } catch (Throwable t) {
            status.setText(getString(R.string.config_invalid, String.valueOf(t.getMessage())));
            if (!quiet) {
                toast(getString(R.string.config_invalid, String.valueOf(t.getMessage())));
            }
        }
    }

    private void format() {
        String config = currentConfig().trim();
        if (config.isEmpty()) {
            return;
        }
        try {
            io.nekohasekai.libbox.StringBox formatted = Libbox.formatConfig(config);
            editor.setText(formatted == null || formatted.getValue() == null ? config : formatted.getValue());
            status.setText(R.string.config_formatted);
        } catch (Throwable t) {
            status.setText(getString(R.string.config_invalid, String.valueOf(t.getMessage())));
        }
    }

    private void save() {
        String config = currentConfig().trim();
        if (config.isEmpty()) {
            toast(getString(R.string.config_empty));
            return;
        }
        try {
            Libbox.checkConfig(config);
        } catch (Throwable t) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.config_invalid_title)
                    .setMessage(String.valueOf(t.getMessage()))
                    .setPositiveButton(R.string.action_save_anyway, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            store();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        store();
    }

    private void store() {
        final String config = currentConfig();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Subscription subscription;
                if (subscriptionId > 0) {
                    subscription = Repo.subscription(ConfigEditorActivity.this, subscriptionId);
                    if (subscription == null) {
                        subscription = new Subscription();
                        subscription.id = 0;
                    }
                } else {
                    subscription = new Subscription();
                }
                if (subscription.id == 0) {
                    subscription.name = getString(R.string.config_default_name);
                    subscription.kind = SubImporter.KIND_CONFIG;
                    subscription.url = "";
                }
                subscription.rawConfig = config;
                subscription.kind = SubImporter.KIND_CONFIG;
                subscription.lastUpdate = System.currentTimeMillis();
                long id;
                if (subscription.id == 0) {
                    id = Repo.insertSubscription(ConfigEditorActivity.this, subscription);
                } else {
                    Repo.updateSubscription(ConfigEditorActivity.this, subscription);
                    id = subscription.id;
                }
                Prefs.setRawSubId(id);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(getString(R.string.config_saved));
                        if (VpnServiceVortex.isActive()) {
                            VpnServiceVortex.restart(ConfigEditorActivity.this);
                        }
                        finish();
                    }
                });
            }
        }, "vortex-config-save").start();
    }

    private void importFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null) {
            toast(getString(R.string.clipboard_empty));
            return;
        }
        CharSequence text = clipboard.getPrimaryClip().getItemCount() > 0
                ? clipboard.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
        if (TextUtils.isEmpty(text)) {
            toast(getString(R.string.clipboard_empty));
            return;
        }
        editor.setText(text.toString());
        check(false);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.config_editor, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_check) {
            check(false);
            return true;
        }
        if (id == R.id.action_format) {
            format();
            return true;
        }
        if (id == R.id.action_paste) {
            importFromClipboard();
            return true;
        }
        if (id == R.id.action_save) {
            save();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}

package com.vortex.vpn.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.vortex.vpn.Prefs;
import com.vortex.vpn.R;
import com.vortex.vpn.cfg.ConfigSettings;
import com.vortex.vpn.core.VpnServiceVortex;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** All client preferences in one screen; changes are pushed to the running engine. */
public class SettingsActivity extends AppCompatActivity {

    private LinearLayout content;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_settings);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        content = findViewById(R.id.content);
        buildSwitches();
        buildDialogs();
    }

    private void buildSwitches() {
        bindSwitch(R.id.sw_ipv6, Prefs.KEY_IPV6, Prefs.getBoolean(Prefs.KEY_IPV6, true));
        bindSwitch(R.id.sw_fakeip, Prefs.KEY_FAKEIP, Prefs.getBoolean(Prefs.KEY_FAKEIP, false));
        bindSwitch(R.id.sw_sniff, Prefs.KEY_SNIFF, Prefs.getBoolean(Prefs.KEY_SNIFF, true));
        bindSwitch(R.id.sw_bypass, Prefs.KEY_ALLOW_BYPASS, Prefs.getBoolean(Prefs.KEY_ALLOW_BYPASS, false));
        bindSwitch(R.id.sw_strict, Prefs.KEY_STRICT_ROUTE, Prefs.getBoolean(Prefs.KEY_STRICT_ROUTE, false));
        bindSwitch(R.id.sw_dns_cache, Prefs.KEY_DNS_CACHE, Prefs.getBoolean(Prefs.KEY_DNS_CACHE, true));
        bindSwitch(R.id.sw_doh, Prefs.KEY_DNS_DOH, Prefs.getBoolean(Prefs.KEY_DNS_DOH, true));
        bindSwitch(R.id.sw_mux, Prefs.KEY_MUX, Prefs.getBoolean(Prefs.KEY_MUX, false));
        bindSwitch(R.id.sw_fragment, Prefs.KEY_TLS_FRAGMENT, Prefs.getBoolean(Prefs.KEY_TLS_FRAGMENT, false));
        bindSwitch(R.id.sw_record_fragment, Prefs.KEY_RECORD_FRAGMENT, Prefs.getBoolean(Prefs.KEY_RECORD_FRAGMENT, false));
        bindSwitch(R.id.sw_auto_select, Prefs.KEY_AUTO_SELECT, Prefs.getBoolean(Prefs.KEY_AUTO_SELECT, false));
        bindSwitch(R.id.sw_auto_start, Prefs.KEY_AUTO_START, Prefs.getBoolean(Prefs.KEY_AUTO_START, false));
        bindSwitch(R.id.sw_sub_auto, Prefs.KEY_SUB_AUTO_UPDATE, Prefs.getBoolean(Prefs.KEY_SUB_AUTO_UPDATE, true));
    }

    private void bindSwitch(int viewId, final String key, boolean initial) {
        MaterialSwitch view = findViewById(viewId);
        view.setChecked(initial);
        view.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setBoolean(key, isChecked);
                apply();
            }
        });
    }

    private void buildDialogs() {
        click(R.id.btn_mode, new Runnable() {
            @Override
            public void run() {
                chooseMode();
            }
        });
        click(R.id.btn_stack, new Runnable() {
            @Override
            public void run() {
                chooseStack();
            }
        });
        click(R.id.btn_mtu, new Runnable() {
            @Override
            public void run() {
                editNumber(R.string.setting_mtu, Prefs.KEY_MTU, Prefs.mtu());
            }
        });
        click(R.id.btn_dns_direct, new Runnable() {
            @Override
            public void run() {
                editText(R.string.setting_dns_direct, Prefs.KEY_DNS_DIRECT, Prefs.getString(Prefs.KEY_DNS_DIRECT, ""));
            }
        });
        click(R.id.btn_dns_remote, new Runnable() {
            @Override
            public void run() {
                editText(R.string.setting_dns_remote, Prefs.KEY_DNS_REMOTE, Prefs.getString(Prefs.KEY_DNS_REMOTE, ""));
            }
        });
        click(R.id.btn_dns_sni, new Runnable() {
            @Override
            public void run() {
                editText(R.string.setting_dns_sni, Prefs.KEY_DNS_DOH_SNI, Prefs.getString(Prefs.KEY_DNS_DOH_SNI, ""));
            }
        });
        click(R.id.btn_url_test, new Runnable() {
            @Override
            public void run() {
                editText(R.string.setting_url_test, Prefs.KEY_URL_TEST_URL, Prefs.getString(Prefs.KEY_URL_TEST_URL, ""));
            }
        });
        click(R.id.btn_sub_interval, new Runnable() {
            @Override
            public void run() {
                editNumber(R.string.setting_sub_interval, Prefs.KEY_SUB_UPDATE_INTERVAL,
                        Prefs.getInt(Prefs.KEY_SUB_UPDATE_INTERVAL, 12));
            }
        });
        click(R.id.btn_user_agent, new Runnable() {
            @Override
            public void run() {
                editText(R.string.setting_user_agent, Prefs.KEY_USER_AGENT, Prefs.getString(Prefs.KEY_USER_AGENT, ""));
            }
        });
        click(R.id.btn_direct_domains, new Runnable() {
            @Override
            public void run() {
                editDomains(R.string.setting_direct_domains, Prefs.KEY_DIRECT_DOMAINS, Prefs.getStringSet(Prefs.KEY_DIRECT_DOMAINS));
            }
        });
        click(R.id.btn_block_domains, new Runnable() {
            @Override
            public void run() {
                editDomains(R.string.setting_block_domains, Prefs.KEY_BLOCK_DOMAINS, Prefs.getStringSet(Prefs.KEY_BLOCK_DOMAINS));
            }
        });
        click(R.id.btn_per_app, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, AppListActivity.class));
            }
        });
        click(R.id.btn_log_level, new Runnable() {
            @Override
            public void run() {
                chooseLogLevel();
            }
        });
        click(R.id.btn_reset, new Runnable() {
            @Override
            public void run() {
                confirmReset();
            }
        });
        click(R.id.btn_logs, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, LogsActivity.class));
            }
        });
        click(R.id.btn_connections, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, ConnectionsActivity.class));
            }
        });
        click(R.id.btn_about, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
            }
        });
        click(R.id.btn_apps_proxy, new Runnable() {
            @Override
            public void run() {
                Prefs.setInt(Prefs.KEY_MODE, ConfigSettings.MODE_BYPASS);
                startActivity(new Intent(SettingsActivity.this, AppListActivity.class));
            }
        });
        updateSummaries();
    }

    private void click(int id, final Runnable action) {
        content.setOnClickListener(null);
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSummaries();
    }

    private void updateSummaries() {
        int mode = Prefs.routeMode();
        summary(R.id.btn_mode, getString(mode == ConfigSettings.MODE_GLOBAL ? R.string.mode_global
                : mode == ConfigSettings.MODE_BYPASS ? R.string.mode_bypass : R.string.mode_smart));
        summary(R.id.btn_stack, Prefs.stack());
        summary(R.id.btn_mtu, String.valueOf(Prefs.mtu()));
        summary(R.id.btn_dns_direct, Prefs.getString(Prefs.KEY_DNS_DIRECT, ""));
        summary(R.id.btn_dns_remote, Prefs.getString(Prefs.KEY_DNS_REMOTE, "")
                + (Prefs.getBoolean(Prefs.KEY_DNS_DOH, true) ? " (DoH)" : " (UDP)"));
        summary(R.id.btn_dns_sni, Prefs.getString(Prefs.KEY_DNS_DOH_SNI, ""));
        summary(R.id.btn_url_test, Prefs.getString(Prefs.KEY_URL_TEST_URL, ""));
        summary(R.id.btn_sub_interval, getString(R.string.hours_format,
                Prefs.getInt(Prefs.KEY_SUB_UPDATE_INTERVAL, 12)));
        String agent = Prefs.getString(Prefs.KEY_USER_AGENT, "");
        summary(R.id.btn_user_agent, agent.isEmpty() ? getString(R.string.setting_default) : agent);
        summary(R.id.btn_direct_domains, getString(R.string.domains_count,
                Prefs.getStringSet(Prefs.KEY_DIRECT_DOMAINS).size()));
        summary(R.id.btn_block_domains, getString(R.string.domains_count,
                Prefs.getStringSet(Prefs.KEY_BLOCK_DOMAINS).size()));
        summary(R.id.btn_per_app, Prefs.perAppEnabled()
                ? getString(R.string.enabled_apps_count, Prefs.perAppList().size())
                : getString(R.string.disabled));
        summary(R.id.btn_log_level, Prefs.getString(Prefs.KEY_LOG_LEVEL, "info"));
    }

    private void summary(int id, CharSequence value) {
        View row = findViewById(id);
        if (row == null) {
            return;
        }
        View summary = row.findViewWithTag("summary");
        if (summary instanceof TextView) {
            ((TextView) summary).setText(value);
        }
    }

    private void chooseMode() {
        final String[] entries = {getString(R.string.mode_global), getString(R.string.mode_smart),
                getString(R.string.mode_bypass)};
        int checked = Prefs.routeMode();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_mode)
                .setSingleChoiceItems(entries, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setInt(Prefs.KEY_MODE, which);
                        dialog.dismiss();
                        updateSummaries();
                        apply();
                    }
                })
                .show();
    }

    private void chooseStack() {
        final String[] values = {"mixed", "gvisor", "system"};
        final String[] entries = {getString(R.string.stack_mixed), getString(R.string.stack_gvisor),
                getString(R.string.stack_system)};
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(Prefs.stack())) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_stack)
                .setSingleChoiceItems(entries, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setString(Prefs.KEY_STACK, values[which]);
                        dialog.dismiss();
                        updateSummaries();
                        if (VpnServiceVortex.isActive()) {
                            VpnServiceVortex.restart(SettingsActivity.this);
                        }
                    }
                })
                .show();
    }

    private void chooseLogLevel() {
        final String[] values = {"trace", "debug", "info", "warn", "error"};
        int checked = 2;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(Prefs.getString(Prefs.KEY_LOG_LEVEL, "info"))) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_log_level)
                .setSingleChoiceItems(values, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setString(Prefs.KEY_LOG_LEVEL, values[which]);
                        dialog.dismiss();
                        updateSummaries();
                        apply();
                    }
                })
                .show();
    }

    private EditText dialogInput(String value, boolean numeric) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setSingleLine(true);
        if (numeric) {
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        } else {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        }
        input.setSelection(input.getText().length());
        LinearLayout wrapper = new LinearLayout(this);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        wrapper.setPadding(padding, padding / 2, padding, 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private View wrap(EditText input) {
        LinearLayout wrapper = new LinearLayout(this);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        wrapper.setPadding(padding, padding / 2, padding, 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private void editText(int titleRes, final String key, String value) {
        final EditText input = dialogInput(value, false);
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setView(wrap(input))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setString(key, input.getText().toString().trim());
                        updateSummaries();
                        apply();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editNumber(int titleRes, final String key, int value) {
        final EditText input = dialogInput(String.valueOf(value), true);
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setView(wrap(input))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            int parsed = Integer.parseInt(input.getText().toString().trim());
                            Prefs.setInt(key, parsed);
                        } catch (NumberFormatException ignored) {
                        }
                        updateSummaries();
                        apply();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editDomains(int titleRes, final String key, Set<String> values) {
        final EditText input = new EditText(this);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setMinLines(5);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            builder.append(value).append('\n');
        }
        input.setText(builder.toString());
        LinearLayout wrapper = new LinearLayout(this);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        wrapper.setPadding(padding, padding / 2, padding, 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(R.string.domains_hint)
                .setView(wrapper)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Set<String> parsed = new LinkedHashSet<>();
                        for (String line : input.getText().toString().split("\\n")) {
                            String value = line.trim().toLowerCase(Locale.ROOT);
                            if (!value.isEmpty()) {
                                parsed.add(value);
                            }
                        }
                        Prefs.setStringSet(key, parsed);
                        updateSummaries();
                        apply();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmReset() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_reset)
                .setMessage(R.string.setting_reset_confirm)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        resetPreferences();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void resetPreferences() {
        Prefs.setInt(Prefs.KEY_MODE, ConfigSettings.MODE_SMART);
        Prefs.setString(Prefs.KEY_STACK, "mixed");
        Prefs.setInt(Prefs.KEY_MTU, 9000);
        Prefs.setBoolean(Prefs.KEY_IPV6, true);
        Prefs.setBoolean(Prefs.KEY_FAKEIP, false);
        Prefs.setBoolean(Prefs.KEY_DNS_CACHE, true);
        Prefs.setBoolean(Prefs.KEY_DNS_DOH, true);
        Prefs.setBoolean(Prefs.KEY_MUX, false);
        Prefs.setBoolean(Prefs.KEY_TLS_FRAGMENT, false);
        Prefs.setBoolean(Prefs.KEY_RECORD_FRAGMENT, false);
        Prefs.setBoolean(Prefs.KEY_SNIFF, true);
        Prefs.setBoolean(Prefs.KEY_AUTO_SELECT, false);
        Prefs.setString(Prefs.KEY_LOG_LEVEL, "info");
        Toast.makeText(this, R.string.setting_reset_done, Toast.LENGTH_SHORT).show();
        recreate();
        apply();
    }

    private void apply() {
        if (VpnServiceVortex.isActive()) {
            VpnServiceVortex.reload(this);
        }
    }
}

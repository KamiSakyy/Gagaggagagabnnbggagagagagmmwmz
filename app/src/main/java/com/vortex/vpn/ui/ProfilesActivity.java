package com.vortex.vpn.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.vortex.vpn.Prefs;
import com.vortex.vpn.R;
import com.vortex.vpn.core.Bridge;
import com.vortex.vpn.core.SubscriptionUpdater;
import com.vortex.vpn.core.VpnServiceVortex;
import com.vortex.vpn.db.Repo;
import com.vortex.vpn.model.Subscription;
import com.vortex.vpn.ui.adapter.SubscriptionAdapter;

import java.util.ArrayList;
import java.util.List;

/** Subscription and config profiles: add, refresh, edit and remove. */
public class ProfilesActivity extends AppCompatActivity {

    private SubscriptionAdapter adapter;
    private SwipeRefreshLayout refresh;
    private TextView empty;
    private FloatingActionButton fab;
    private List<Subscription> subscriptions = new ArrayList<>();
    private long lastAutoRefresh;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profiles);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_profiles);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        refresh = findViewById(R.id.refresh);
        empty = findViewById(R.id.text_empty);
        fab = findViewById(R.id.fab);

        adapter = new SubscriptionAdapter(this, new SubscriptionAdapter.Listener() {
            @Override
            public void onRefresh(Subscription subscription) {
                refreshSubscription(subscription);
            }

            @Override
            public void onEdit(Subscription subscription) {
                openProfile(subscription);
            }

            @Override
            public void onMenu(View anchor, Subscription subscription) {
                showMenu(subscription);
            }
        });

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        refresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                refreshAll();
            }
        });
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddDialog();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        maybeAutoRefresh();
    }

    private void load() {
        subscriptions = Repo.subscriptions(this);
        adapter.submit(subscriptions);
        empty.setVisibility(subscriptions.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void maybeAutoRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastAutoRefresh < 60000) {
            return;
        }
        lastAutoRefresh = now;
        for (Subscription subscription : subscriptions) {
            if (subscription.autoUpdate && subscription.updateIntervalHours > 0
                    && (subscription.url != null && !subscription.url.isEmpty())) {
                long interval = subscription.updateIntervalHours * 3600_000L;
                if (now - subscription.lastUpdate > interval) {
                    refreshSubscription(subscription);
                    break;
                }
            }
        }
    }

    private void refreshAll() {
        refresh.setRefreshing(false);
        if (subscriptions.isEmpty()) {
            showAddDialog();
            return;
        }
        for (Subscription subscription : subscriptions) {
            if (subscription.url != null && !subscription.url.isEmpty()) {
                refreshSubscription(subscription);
            }
        }
    }

    private void refreshSubscription(final Subscription subscription) {
        if (TextUtils.isEmpty(subscription.url)) {
            toast(getString(R.string.profile_local_only));
            return;
        }
        adapter.setBusy(subscription.id);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final SubscriptionUpdater.Result result = SubscriptionUpdater.refresh(
                        ProfilesActivity.this, subscription.id);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        adapter.setBusy(-1);
                        load();
                        toast(result.message);
                        if (result.ok && VpnServiceVortex.isActive()) {
                            VpnServiceVortex.reload(ProfilesActivity.this);
                        }
                    }
                });
            }
        }, "vortex-sub-refresh").start();
    }

    private void openProfile(final Subscription subscription) {
        boolean isConfig = subscription.rawConfig != null && !subscription.rawConfig.trim().isEmpty();
        if (isConfig || SubscriptionUpdater.class != null) {
            String[] actions = isConfig
                    ? new String[]{getString(R.string.profile_use_config), getString(R.string.profile_edit_config),
                    getString(R.string.action_rename), getString(R.string.action_delete)}
                    : new String[]{getString(R.string.action_refresh), getString(R.string.action_rename),
                    getString(R.string.action_delete)};
            new MaterialAlertDialogBuilder(this)
                    .setTitle(subscription.name)
                    .setItems(actions, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            boolean isConfig2 = subscription.rawConfig != null && !subscription.rawConfig.trim().isEmpty();
                            if (isConfig2) {
                                if (which == 0) {
                                    useRawConfig(subscription);
                                } else if (which == 1) {
                                    editConfig(subscription.id);
                                } else if (which == 2) {
                                    rename(subscription);
                                } else {
                                    confirmDelete(subscription);
                                }
                            } else {
                                if (which == 0) {
                                    refreshSubscription(subscription);
                                } else if (which == 1) {
                                    rename(subscription);
                                } else {
                                    confirmDelete(subscription);
                                }
                            }
                        }
                    })
                    .show();
        }
    }

    private void useRawConfig(Subscription subscription) {
        Prefs.setRawSubId(subscription.id);
        toast(getString(R.string.profile_config_active, subscription.name));
        if (VpnServiceVortex.isActive()) {
            VpnServiceVortex.restart(this);
        }
    }

    private void editConfig(long subscriptionId) {
        Intent intent = new Intent(this, ConfigEditorActivity.class);
        intent.putExtra(ConfigEditorActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId);
        startActivity(intent);
    }

    private void rename(final Subscription subscription) {
        final EditText input = new EditText(this);
        input.setText(subscription.name);
        input.setSelection(input.getText().length());
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        subscription.name = input.getText().toString().trim();
                        Repo.updateSubscription(ProfilesActivity.this, subscription);
                        load();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete(final Subscription subscription) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete)
                .setMessage(getString(R.string.profile_delete_confirm, subscription.name))
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Repo.deleteSubscription(ProfilesActivity.this, subscription.id);
                        if (Prefs.rawSubId() == subscription.id) {
                            Prefs.setRawSubId(0);
                        }
                        load();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMenu(final Subscription subscription) {
        String[] actions = new String[]{
                getString(R.string.action_refresh),
                getString(R.string.action_edit_config),
                getString(R.string.profile_use_config),
                getString(R.string.action_copy_link),
                getString(R.string.action_delete)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(subscription.name)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                refreshSubscription(subscription);
                                break;
                            case 1:
                                editConfig(subscription.id);
                                break;
                            case 2:
                                useRawConfig(subscription);
                                break;
                            case 3:
                                copyLink(subscription);
                                break;
                            default:
                                confirmDelete(subscription);
                                break;
                        }
                    }
                })
                .show();
    }

    private void copyLink(Subscription subscription) {
        String value = TextUtils.isEmpty(subscription.url) ? subscription.name : subscription.url;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("vortex", value));
            toast(getString(R.string.copied));
        }
    }

    private void showAddDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_profile, null);
        final EditText name = view.findViewById(R.id.input_name);
        final EditText link = view.findViewById(R.id.input_link);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()
                && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (text != null && text.length() > 8) {
                link.setText(text.toString().trim());
                link.setSelection(link.getText().length());
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.profile_add)
                .setView(view)
                .setPositiveButton(R.string.action_add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        addProfile(name.getText().toString().trim(), link.getText().toString().trim());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addProfile(final String title, final String input) {
        if (input.isEmpty()) {
            toast(getString(R.string.profile_empty_input));
            return;
        }
        toast(getString(R.string.profile_importing));
        new Thread(new Runnable() {
            @Override
            public void run() {
                long id = SubscriptionUpdater.addFromInput(ProfilesActivity.this, input, title);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        load();
                        if (id > 0 && VpnServiceVortex.isActive()) {
                            VpnServiceVortex.reload(ProfilesActivity.this);
                        }
                    }
                });
            }
        }, "vortex-sub-add").start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.profiles, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add) {
            showAddDialog();
            return true;
        }
        if (id == R.id.action_new_config) {
            startActivity(new Intent(this, ConfigEditorActivity.class));
            return true;
        }
        if (id == R.id.action_logs) {
            startActivity(new Intent(this, LogsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        Bridge.appendLog("I", message);
    }
}

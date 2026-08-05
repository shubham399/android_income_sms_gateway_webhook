package tech.bogomolov.incomingsmsgateway;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private Context context;
    private ListAdapter listAdapter;

    private static final int PERMISSION_CODE = 0;
    private static final int BACKFILL_PERMISSION_CODE = 1;

    // Rule key a pending backfill-permission request is waiting for; null means
    // the global (all rules) backfill.
    private String pendingBackfillKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ArrayList<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECEIVE_SMS);
        }
        // Android 13+ gates the foreground-service "F" indicator behind a runtime
        // permission. Without it the service still runs and forwards SMS, but the
        // persistent notification never shows (issue #77). Treated as best-effort:
        // we ask for it, but its denial does not block the app like RECEIVE_SMS does.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (permissions.isEmpty()) {
            showList();
        } else {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        // Backfill needs READ_SMS, which is separate from the RECEIVE_SMS flow
        // that gates the main list. Denial just cancels the backfill.
        if (requestCode == BACKFILL_PERMISSION_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (!permissions[i].equals(Manifest.permission.READ_SMS)) {
                    continue;
                }
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    startBackfill();
                } else {
                    Toast.makeText(this, R.string.backfill_permission_needed, Toast.LENGTH_LONG).show();
                }
                return;
            }
            return;
        }        if (requestCode != PERMISSION_CODE) {
            return;
        }
        for (int i = 0; i < permissions.length; i++) {
            if (!permissions[i].equals(Manifest.permission.RECEIVE_SMS)) {
                continue;
            }

            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                showList();
            } else {
                showInfo(getResources().getString(R.string.permission_needed));
            }

            return;
        }

        // RECEIVE_SMS wasn't part of this result (it was already granted, and only
        // the best-effort POST_NOTIFICATIONS was requested), so proceed as long as
        // RECEIVE_SMS is in fact still granted. Re-checking also handles the
        // empty-array case Android delivers when the dialog is cancelled.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            showList();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Failures accrue in the background, so refresh the retry counter each
        // time the activity comes forward.
        invalidateOptionsMenu();
        // Rules can also change outside this screen (Settings -> import a backup),
        // so reload the list. Null until the permission flow has let showList() run.
        if (listAdapter != null) {
            listAdapter.clear();
            listAdapter.addAll(ForwardingConfig.getAll(this));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.action_bar_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem retryItem = menu.findItem(R.id.action_bar_retry_failed);
        int count = FailedMessage.getCount(this);
        retryItem.setVisible(count > 0);
        if (count > 0) {
            retryItem.setTitle(getString(R.string.menu_retry_failed, count));
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_bar_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }

        if (id == R.id.action_bar_activity_log) {
            startActivity(new Intent(this, ActivityLogActivity.class));
            return true;
        }

        if (id == R.id.action_bar_backfill) {
            requestBackfill(null);
            return true;
        }

        if (id == R.id.action_bar_retry_failed) {
            int count = FailedMessage.getCount(this);
            FailedMessage.retryAll(this);
            Toast.makeText(this, getString(R.string.retry_failed_toast, count), Toast.LENGTH_LONG).show();
            invalidateOptionsMenu();
            return true;
        }

        if (id == R.id.action_bar_syslogs) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            View view = getLayoutInflater().inflate(R.layout.syslogs, null);

            String logs = "";
            try {
                String[] command = new String[]{
                        "logcat", "-d", "*:E", "-m", "1000",
                        "|", "grep", "tech.bogomolov.incomingsmsgateway"};
                Process process = Runtime.getRuntime().exec(command);

                BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    logs += line + "\n";
                }
            } catch (IOException ex) {
                logs = "getLog failed";
            }

            TextView logsTextContainer = view.findViewById(R.id.syslogs_text);
            logsTextContainer.setText(logs);

            TextView version = view.findViewById(R.id.syslogs_version);
            version.setText("v" + BuildConfig.VERSION_NAME);

            builder.setView(view);
            builder.setNegativeButton(R.string.btn_close, null);
            builder.setNeutralButton(R.string.btn_clear, null);

            final AlertDialog dialog = builder.show();
            Objects.requireNonNull(dialog.getWindow())
                    .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(view1 -> {
                        String[] command = new String[]{"logcat", "-c"};
                        try {
                            Runtime.getRuntime().exec(command);
                        } catch (IOException e) {
                            Log.e("SmsGateway", "log clear error: " + e);
                        }
                        dialog.cancel();
                    });
        }

        return super.onOptionsItemSelected(item);
    }

    // Entry point for the per-rule Backfill button in the list (ListAdapter).
    // Never falls back to the global "all rules" backfill: a null key means the
    // rule could not be identified, so the user gets an error instead of an
    // unexpected full-inbox run.
    public void requestBackfillForConfig(String configKey) {
        if (configKey == null) {
            Toast.makeText(this, R.string.backfill_rule_unknown, Toast.LENGTH_LONG).show();
            return;
        }
        requestBackfill(configKey);
    }

    // Requests READ_SMS if needed, then runs the backfill for the pending rule.
    private void requestBackfill(String configKey) {
        this.pendingBackfillKey = configKey;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_SMS}, BACKFILL_PERMISSION_CODE);
        } else {
            startBackfill();
        }
    }

    // Confirms with the user (a full inbox can generate many requests), then
    // kicks off the backfill in the background via WorkManager. Consumes
    // pendingBackfillKey so a later permission grant can't reuse a stale rule.
    private void startBackfill() {
        String configKey = this.pendingBackfillKey;
        this.pendingBackfillKey = null;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.backfill_confirm_title);
        builder.setMessage(configKey == null
                ? R.string.backfill_confirm_message
                : R.string.backfill_confirm_message_rule);
        builder.setPositiveButton(R.string.btn_start, (dialog, which) -> {
            BackfillWorker.enqueue(this, configKey);
            Toast.makeText(this, R.string.backfill_started, Toast.LENGTH_LONG).show();
        });
        builder.setNegativeButton(R.string.btn_cancel, null);
        builder.show();
    }

    private void showList() {
        context = this;
        ListView listview = findViewById(R.id.listView);

        ArrayList<ForwardingConfig> configs = ForwardingConfig.getAll(context);

        // First-run / empty state: point the user at the + button instead of
        // leaving a blank screen.
        showInfo(configs.isEmpty() ? getString(R.string.empty_list_hint) : "");

        listAdapter = new ListAdapter(configs, context);
        listview.setAdapter(listAdapter);

        // Keep the empty-state hint in sync as rules are added or deleted.
        listAdapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                showInfo(listAdapter.getCount() == 0 ? getString(R.string.empty_list_hint) : "");
            }
        });

        FloatingActionButton fab = findViewById(R.id.btn_add);
        fab.setOnClickListener(this.showAddDialog());

        if (!this.isServiceRunning()) {
            this.startService();
        }
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (tech.bogomolov.incomingsmsgateway.SmsReceiverService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void startService() {
        Context appContext = getApplicationContext();
        Intent intent = new Intent(this, SmsReceiverService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }

    private void showInfo(String text) {
        TextView notice = findViewById(R.id.info_notice);
        notice.setText(text);
        // The icon above the notice should disappear together with the text.
        findViewById(R.id.empty_state).setVisibility(
                text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View.OnClickListener showAddDialog() {
        return v -> {
            (new ForwardingConfigDialog(context, getLayoutInflater(), listAdapter)).showNew();
        };
    }
}

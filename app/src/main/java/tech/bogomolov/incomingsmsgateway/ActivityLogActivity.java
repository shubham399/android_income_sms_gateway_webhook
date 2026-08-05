package tech.bogomolov.incomingsmsgateway;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Shows the per-routing-parameter delivery log ({@link ActivityLog}). A dropdown
 * picks the forwarding rule, the list below shows that rule's entries newest
 * first, and the action-bar Clear button empties the selected rule's log.
 */
public class ActivityLogActivity extends AppCompatActivity {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private final ArrayList<ForwardingConfig> configs = new ArrayList<>();
    private Spinner spinner;
    private TextView emptyView;
    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        spinner = findViewById(R.id.log_rule_spinner);
        emptyView = findViewById(R.id.log_empty);
        listView = findViewById(R.id.log_list);

        configs.addAll(ForwardingConfig.getAll(this));
        if (configs.isEmpty()) {
            spinner.setEnabled(false);
            emptyView.setText(R.string.activity_log_no_rules);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }

        List<String> labels = new ArrayList<>();
        for (ForwardingConfig config : configs) {
            labels.add(labelFor(config));
        }

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                reload(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Logs accrue in the background (RequestWorker, BackfillWorker); refresh
        // whenever the screen comes forward. onCreate's spinner selection already
        // fired reload for position 0, so this only re-shows newer entries.
        if (!configs.isEmpty() && spinner.getSelectedItemPosition() >= 0) {
            reload(spinner.getSelectedItemPosition());
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_activity_log, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_clear_log) {
            if (!configs.isEmpty()) {
                int position = spinner.getSelectedItemPosition();
                ActivityLog.clearForConfig(this, configs.get(position).getKey());
                reload(position);
                Toast.makeText(this, R.string.activity_log_cleared, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void reload(int position) {
        List<ActivityLog.LogEntry> entries =
                ActivityLog.getForConfig(this, configs.get(position).getKey());
        if (entries.isEmpty()) {
            emptyView.setText(R.string.activity_log_empty);
            emptyView.setVisibility(View.VISIBLE);
            listView.setAdapter(null);
        } else {
            emptyView.setVisibility(View.GONE);
            listView.setAdapter(new LogAdapter(entries));
        }
    }

    private String labelFor(ForwardingConfig config) {
        String sender = config.getSender();
        if (sender.equals(getString(R.string.asterisk))) {
            sender = getString(R.string.any);
        }
        return sender + " — " + config.getUrl();
    }

    private String eventLabel(String event) {
        if (ActivityLog.EVENT_SUCCESS.equals(event)) {
            return getString(R.string.log_event_success);
        }
        if (ActivityLog.EVENT_RETRY.equals(event)) {
            return getString(R.string.log_event_retry);
        }
        if (ActivityLog.EVENT_FAILED.equals(event)) {
            return getString(R.string.log_event_failed);
        }
        if (ActivityLog.EVENT_BACKFILL.equals(event)) {
            return getString(R.string.log_event_backfill);
        }
        return getString(R.string.log_event_queued);
    }

    private class LogAdapter extends ArrayAdapter<ActivityLog.LogEntry> {
        private final LayoutInflater inflater;

        LogAdapter(List<ActivityLog.LogEntry> entries) {
            super(ActivityLogActivity.this, R.layout.activity_log_item, entries);
            this.inflater = LayoutInflater.from(ActivityLogActivity.this);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = inflater.inflate(R.layout.activity_log_item, parent, false);
            }

            ActivityLog.LogEntry entry = getItem(position);
            String stamp = TIME_FORMAT.format(new Date(entry.timestamp));

            TextView header = row.findViewById(R.id.log_header);
            header.setText(stamp + " · " + eventLabel(entry.event) + " · " + entry.sender);

            TextView content = row.findViewById(R.id.log_content);
            content.setVisibility(entry.content.isEmpty() ? View.GONE : View.VISIBLE);
            content.setText(entry.content);

            TextView detail = row.findViewById(R.id.log_detail);
            detail.setText(entry.detail.isEmpty()
                    ? getString(R.string.activity_log_detail_empty)
                    : entry.detail);

            return row;
        }
    }
}

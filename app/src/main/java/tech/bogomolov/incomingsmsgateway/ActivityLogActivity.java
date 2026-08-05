package tech.bogomolov.incomingsmsgateway;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shows the time-based delivery log ({@link ActivityLog}) across every routing
 * parameter, newest first. No per-rule filter: each row labels which forwarding
 * rule it belongs to, and the action-bar Clear button empties the whole log.
 */
public class ActivityLogActivity extends AppCompatActivity {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private TextView emptyView;
    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        emptyView = findViewById(R.id.log_empty);
        listView = findViewById(R.id.log_list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Logs accrue in the background (RequestWorker, BackfillWorker); refresh
        // whenever the screen comes forward.
        reload();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_activity_log, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_clear_log) {
            ActivityLog.clearAll(this);
            reload();
            Toast.makeText(this, R.string.activity_log_cleared, Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void reload() {
        List<ActivityLog.LogEntry> entries = ActivityLog.getAll(this);
        if (entries.isEmpty()) {
            emptyView.setText(R.string.activity_log_empty);
            emptyView.setVisibility(View.VISIBLE);
            listView.setAdapter(null);
        } else {
            emptyView.setVisibility(View.GONE);
            listView.setAdapter(new LogAdapter(entries, ruleLabels()));
        }
    }

    private Map<String, String> ruleLabels() {
        Map<String, String> labels = new HashMap<>();
        for (ForwardingConfig config : ForwardingConfig.getAll(this)) {
            labels.put(config.getKey(), labelFor(config));
        }
        return labels;
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
        private final Map<String, String> ruleLabels;

        LogAdapter(List<ActivityLog.LogEntry> entries, Map<String, String> ruleLabels) {
            super(ActivityLogActivity.this, R.layout.activity_log_item, entries);
            this.inflater = LayoutInflater.from(ActivityLogActivity.this);
            this.ruleLabels = ruleLabels;
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

            TextView ruleView = row.findViewById(R.id.log_rule);
            String rule = ruleLabels.get(entry.configKey);
            if (rule == null) {
                ruleView.setVisibility(View.GONE);
            } else {
                ruleView.setText(rule);
                ruleView.setVisibility(View.VISIBLE);
            }

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

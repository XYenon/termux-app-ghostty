package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.theme.NightMode;
import com.termux.shared.theme.ThemeUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.ProgramStatusStore;

import java.util.List;

public class TermuxSessionsListViewController extends ArrayAdapter<TermuxSession> implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    final TermuxActivity mActivity;

    final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
    final StyleSpan italicSpan = new StyleSpan(Typeface.ITALIC);

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList) {
        super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, sessionList);
        this.mActivity = activity;
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View sessionRowView = convertView;
        if (sessionRowView == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false);
        }

        TextView sessionTitleView = sessionRowView.findViewById(R.id.session_title);
        TextView statusView = sessionRowView.findViewById(R.id.session_program_status);

        TerminalSession sessionAtRow = getItem(position).getTerminalSession();
        if (sessionAtRow == null) {
            sessionTitleView.setText("null session");
            statusView.setVisibility(View.GONE);
            return sessionRowView;
        }

        boolean shouldEnableDarkTheme = ThemeUtils.shouldEnableDarkTheme(mActivity, NightMode.getAppNightMode().getName());

        sessionRowView.setBackground(ContextCompat.getDrawable(mActivity,
            shouldEnableDarkTheme ? R.drawable.session_background_black_selected
                : R.drawable.session_background_selected));

        String name = sessionAtRow.mSessionName;
        String sessionTitle = sessionAtRow.getTitle();

        String numberPart = "[" + (position + 1) + "] ";
        String sessionNamePart = (TextUtils.isEmpty(name) ? "" : name);
        String sessionTitlePart = (TextUtils.isEmpty(sessionTitle) ? "" : ((sessionNamePart.isEmpty() ? "" : "\n") + sessionTitle));

        String fullSessionTitle = numberPart + sessionNamePart + sessionTitlePart;
        SpannableString fullSessionTitleStyled = new SpannableString(fullSessionTitle);
        fullSessionTitleStyled.setSpan(boldSpan, 0, numberPart.length() + sessionNamePart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        fullSessionTitleStyled.setSpan(italicSpan, numberPart.length() + sessionNamePart.length(), fullSessionTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        sessionTitleView.setText(fullSessionTitleStyled);

        boolean sessionRunning = sessionAtRow.isRunning();

        if (sessionRunning) {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        int defaultColor = shouldEnableDarkTheme ? Color.WHITE : Color.BLACK;
        int color = sessionRunning || sessionAtRow.getExitStatus() == 0 ? defaultColor : Color.RED;
        sessionTitleView.setTextColor(color);

        List<ProgramStatusStore.Record> statuses = sessionAtRow.getProgramStatuses();
        statusView.setVisibility(statuses.isEmpty() ? View.GONE : View.VISIBLE);
        statusView.setTextColor(defaultColor);
        // Newest reports first, with full records available by tapping the summary.
        String statusText = programStatusText(statuses);
        statusView.setText(statusText);
        statusView.setContentDescription(mActivity.getString(
            R.string.program_status_details, position + 1) + ": " + statusText);
        statusView.setOnClickListener(view -> new AlertDialog.Builder(mActivity)
            .setTitle(mActivity.getString(R.string.program_status_details, position + 1))
            .setMessage(programStatusText(sessionAtRow.getProgramStatuses()))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.program_status_dismiss_finished,
                (dialog, which) -> sessionAtRow.dismissFinishedProgramStatuses())
            .show());
        return sessionRowView;
    }

    private String programStatusText(List<ProgramStatusStore.Record> records) {
        String[] states = mActivity.getResources().getStringArray(R.array.program_status_states);
        String[] kinds = mActivity.getResources().getStringArray(R.array.program_status_kinds);
        StringBuilder text = new StringBuilder();
        for (int i = records.size() - 1; i >= 0; i--) {
            ProgramStatusStore.Record record = records.get(i);
            if (text.length() > 0) text.append("\n\n");
            text.append(states[record.state]);
            if (record.kind != ProgramStatusStore.KIND_NONE)
                text.append(" · ").append(kinds[record.kind]);
            if (record.progress >= 0) text.append(" · ").append(record.progress).append('%');
            if (!record.app.isEmpty()) text.append(" · ").append(record.app);
            if (!record.id.isEmpty()) text.append(" · ").append(record.id);
            if (!record.title.isEmpty()) text.append(" · ").append(record.title);
            if (!record.message.isEmpty()) text.append("\n").append(record.message);
        }
        return text.toString();
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        TermuxSession clickedSession = getItem(position);
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
        mActivity.getDrawer().closeDrawers();
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        final TermuxSession selectedSession = getItem(position);
        mActivity.getTermuxTerminalSessionClient().renameSession(selectedSession.getTerminalSession());
        return true;
    }

}

package net.kenevans.android.blecardiacmonitor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;

public class SessionManagerActivity extends ListActivity implements IConstants {
    private SessionListAdapter mSessionListAdapter;
    private BCMDbAdapter mDbAdapter;
    private File mDataDir;
    private RestoreTask mRestoreTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setDisplayHomeAsUpEnabled(false);

        // Set result OK in case the user backs out
        setResult(Activity.RESULT_OK);

        // Get the database name from the default preferences
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(this);
        String prefString = prefs.getString(PREF_DATA_DIRECTORY, null);
        if (prefString == null) {
            Utils.errMsg(this, "Cannot find the name of the data directory");
            return;
        }

        // Open the database
        mDataDir = new File(prefString);
        if (mDataDir == null) {
            Utils.errMsg(this, "Database directory is null");
            return;
        }
        if (!mDataDir.exists()) {
            Utils.errMsg(this, "Cannot find database directory: " + mDataDir);
            mDataDir = null;
            return;
        }
        mDbAdapter = new BCMDbAdapter(this, mDataDir);
        mDbAdapter.open();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, this.getClass().getSimpleName() + ": onResume");
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, this.getClass().getSimpleName() + ": onPause");
        super.onPause();
        if (mSessionListAdapter != null) {
            mSessionListAdapter.clear();
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, this.getClass().getSimpleName() + ": onDestroy");
        super.onDestroy();
        if (mDbAdapter != null) {
            mDbAdapter.close();
            mDbAdapter = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_session_manager, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        switch (item.getItemId()) {
            case R.id.menu_plot:
                plot();
                return true;
            case R.id.menu_discard:
                promptToDiscardSession();
                return true;
            case R.id.menu_save:
                saveSessions();
                return true;
            case R.id.menu_save_combined:
                saveCombinedSessions();
                return true;
            case R.id.menu_save_gpx:
                saveSessionsAsGpx();
                return true;
            case R.id.menu_refresh:
                refresh();
                return true;
            case R.id.menu_check_all:
                setAllSessionsChecked(true);
                return true;
            case R.id.menu_check_none:
                setAllSessionsChecked(false);
                return true;
            case R.id.menu_save_database:
                saveDatabase();
                return true;
            case R.id.menu_restore_database:
                checkRestoreDatabase();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Calls the plot activity for the selected sessions.
     */
    /**
     * Calls the plot activity.
     */
    public void plot() {
        ArrayList<Session> checkedSessions = mSessionListAdapter
                .getCheckedSessions();
        if (checkedSessions == null || checkedSessions.size() == 0) {
            Utils.errMsg(this, "There are no sessions to plot");
            return;
        }
        if (checkedSessions.size() > 1) {
            Utils.errMsg(this,
                    "Only one session may be checked for this operation");
            return;
        }
        Session session = checkedSessions.get(0);
        long startDate = session.getStartDate();
        // long endDate = session.getEndDate();
        Intent intent = new Intent(SessionManagerActivity.this,
                PlotActivity.class);
        // Plot the session
        intent.putExtra(PLOT_SESSION_CODE, true);
        intent.putExtra(PLOT_SESSION_START_TIME_CODE, startDate);
        // // This is not currently used
        // intent.putExtra(PLOT_SESSION_END_TIME_CODE, endDate);
        startActivityForResult(intent, REQUEST_PLOT_CODE);
    }

    /**
     * Merges the selected sessions.
     */
    public void mergeSessions() {
        Utils.infoMsg(this, "Not implented yet");
    }

    /**
     * Splits the selected sessions.
     */
    public void splitSessions() {
        Utils.infoMsg(this, "Not implented yet");
    }

    /**
     * Saves the selected sessions.
     */
    public void saveSessions() {
        ArrayList<Session> checkedSessions = mSessionListAdapter
                .getCheckedSessions();
        if (checkedSessions == null || checkedSessions.size() == 0) {
            Utils.errMsg(this, "There are no sessions to save");
            return;
        }
        if (mDataDir == null) {
            Utils.errMsg(this, "Cannot determine directory for save");
            return;
        }
        int nErrors = 0;
        int nWriteErrors = 0;
        String errMsg = "Error saving sessions:\n";
        String fileNames = "Saved to:\n";
        BufferedWriter out = null;
        String fileName = null;
        long startDate = INVALID_DATE;
        File file = null;
        FileWriter writer = null;
        for (Session session : checkedSessions) {
            startDate = session.getStartDate();
            fileName = session.getName() + ".csv";
            try {
                file = new File(mDataDir, fileName);
                writer = new FileWriter(file);
                out = new BufferedWriter(writer);
                // Write the session data
                nWriteErrors = writeSessionDataToCvsFile(startDate, out);
                if (nWriteErrors > 0) {
                    nErrors += nWriteErrors;
                    errMsg += "  " + session.getName();
                }
                fileNames += "  " + file.getName() + "\n";
            } catch (Exception ex) {
                nErrors++;
                errMsg += "  " + session.getName();
            } finally {
                try {
                    out.close();
                } catch (Exception ex) {
                    // Do nothing
                }
            }
        }
        String msg = "Directory:\n" + mDataDir + "\n";
        if (nErrors > 0) {
            msg += errMsg;
        }
        msg += fileNames;
        if (nErrors > 0) {
            Utils.errMsg(this, msg);
        } else {
            Utils.infoMsg(this, msg);
        }
    }

    /**
     * Saves the selected sessions as a combined session.
     */
    public void saveCombinedSessions() {
        ArrayList<Session> checkedSessions = mSessionListAdapter
                .getCheckedSessions();
        if (checkedSessions == null || checkedSessions.size() == 0) {
            Utils.errMsg(this, "There are no sessions to combine and save");
            return;
        }
        if (mDataDir == null) {
            Utils.errMsg(this, "Cannot determine directory for combined save");
            return;
        }
        // Need to sort in order of increasing startTime
        Collections.sort(checkedSessions, new Comparator<Session>() {
            @Override
            public int compare(Session lhs, Session rhs) {
                if (lhs.getStartDate() == rhs.getStartDate()) {
                    return 0;
                } else if (lhs.getStartDate() > rhs.getStartDate()) {
                    return 1;
                } else {
                    return -1;
                }
            }
        });
        int nErrors = 0;
        int nWriteErrors = 0;
        String errMsg = "Error saving combined sessions:\n";
        String fileNames = "Saved to:\n";
        BufferedWriter out = null;
        // Use the name of the first session
        String fileName = checkedSessions.get(0).getName() + "-Combined.csv";
        long startDate = INVALID_DATE;
        File file = null;
        FileWriter writer = null;
        try {
            file = new File(mDataDir, fileName);
            writer = new FileWriter(file);
            out = new BufferedWriter(writer);
            boolean first = true;
            for (Session session : checkedSessions) {
                startDate = session.getStartDate();
                // Write a blank line to separate sessions
                if (first) {
                    first = false;
                } else {
                    out.write("\n");
                }
                // Write the session data
                nWriteErrors = writeSessionDataToCvsFile(startDate, out);
                if (nWriteErrors > 0) {
                    nErrors += nWriteErrors;
                    errMsg += "  " + session.getName();
                }
            }
            fileNames += "  " + file.getName() + "\n";
        } catch (Exception ex) {
            nErrors++;
            errMsg += "  " + "Writing combined file";
        } finally {
            try {
                out.close();
            } catch (Exception ex) {
                // Do nothing
            }
        }
        String msg = "Directory:\n" + mDataDir + "\n";
        if (nErrors > 0) {
            msg += errMsg;
        }
        msg += fileNames;
        if (nErrors > 0) {
            Utils.errMsg(this, msg);
        } else {
            Utils.infoMsg(this, msg);
        }
    }

    /**
     * Writes the session data for the given startDate to the given
     * BufferedWriter.
     *
     * @param startDate
     * @param out
     * @return
     */
    private int writeSessionDataToCvsFile(long startDate, BufferedWriter out) {
        Cursor cursor = null;
        int nErrors = 0;
        try {
            cursor = mDbAdapter.fetchAllHrRrDateDataForStartDate(startDate);
            int indexDate = cursor.getColumnIndex(COL_DATE);
            int indexHr = cursor.getColumnIndex(COL_HR);
            int indexRr = cursor.getColumnIndex(COL_RR);
            // Loop over items
            cursor.moveToFirst();
            String dateStr, hrStr, rrStr, line;
            long dateNum = INVALID_DATE;
            while (cursor.isAfterLast() == false) {
                dateStr = INVALID_STRING;
                if (indexDate > -1) {
                    dateNum = cursor.getLong(indexDate);
                    dateStr = sessionSaveFormatter.format(new Date(dateNum));
                }
                hrStr = INVALID_STRING;
                if (indexHr > -1) {
                    hrStr = cursor.getString(indexHr);
                }
                rrStr = INVALID_STRING;
                if (indexRr > -1) {
                    rrStr = cursor.getString(indexRr);
                }
                line = dateStr + SAVE_SESSION_DELIM + hrStr
                        + SAVE_SESSION_DELIM + rrStr + "\n";
                out.write(line);
                cursor.moveToNext();
            }
        } catch (Exception ex) {
            nErrors++;
        } finally {
            try {
                cursor.close();
            } catch (Exception ex) {
                // Do nothing
            }
        }
        return nErrors;
    }

    /**
     * Saves the selected sessions as GPX files.
     */
    public void saveSessionsAsGpx() {
        ArrayList<Session> checkedSessions = mSessionListAdapter
                .getCheckedSessions();
        if (checkedSessions == null || checkedSessions.size() == 0) {
            Utils.errMsg(this, "There are no sessions to save");
            return;
        }
        if (mDataDir == null) {
            Utils.errMsg(this, "Cannot determine directory for save");
            return;
        }
        int nErrors = 0;
        String errMsg = "Error saving sessions:\n";
        String fileNames = "Saved to:\n";
        BufferedWriter out = null;
        Cursor cursor = null;
        String fileName = null;
        long startDate = INVALID_DATE;
        File file = null;
        FileWriter writer = null;
        String name = "BLE Cardiac Monitor";
        SimpleDateFormat formatter = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            PackageManager pm = getPackageManager();
            PackageInfo po = pm.getPackageInfo(this.getPackageName(), 0);
            name = "BLE Cardiac Monitor" + " " + po.versionName;
        } catch (Exception ex) {
            name = "BLE Cardiac Monitor";
        }
        for (Session session : checkedSessions) {
            startDate = session.getStartDate();
            fileName = session.getName() + ".gpx";
            try {
                file = new File(mDataDir, fileName);
                writer = new FileWriter(file);
                out = new BufferedWriter(writer);
                // Write the beginning lines
                out.write(String.format(GPXUtils.GPX_FILE_START_LINES, name,
                        formatter.format(new Date())));
                cursor = mDbAdapter.fetchAllHrDateDataForStartDate(startDate);
                int indexDate = cursor.getColumnIndex(COL_DATE);
                int indexHr = cursor.getColumnIndex(COL_HR);
                // Loop over items
                cursor.moveToFirst();
                String hrStr, line;
                long dateNum = INVALID_DATE;
                while (cursor.isAfterLast() == false) {
                    if (indexDate > -1) {
                        dateNum = cursor.getLong(indexDate);
                    }
                    hrStr = INVALID_STRING;
                    if (indexHr > -1) {
                        hrStr = cursor.getString(indexHr);
                    }
                    if (hrStr.equals(INVALID_STRING)) {
                        continue;
                    }
                    line = String.format(GPXUtils.GPX_FILE_TRACK_LINES,
                            formatter.format(new Date(dateNum)), hrStr);
                    out.write(line);
                    cursor.moveToNext();
                }
                out.write(GPXUtils.GPX_FILE_END_LINES);
                fileNames += "  " + file.getName() + "\n";
            } catch (Exception ex) {
                nErrors++;
                errMsg += "  " + session.getName();
            } finally {
                try {
                    cursor.close();
                } catch (Exception ex) {
                    // Do nothing
                }
                try {
                    out.close();
                } catch (Exception ex) {
                    // Do nothing
                }
            }
        }
        String msg = "Directory:\n" + mDataDir + "\n";
        if (nErrors > 0) {
            msg += errMsg;
        }
        msg += fileNames;
        if (nErrors > 0) {
            Utils.errMsg(this, msg);
        } else {
            Utils.infoMsg(this, msg);
        }
    }

    /**
     * Prompts to discards the selected sessions. The method doDiscard will do
     * the actual discarding, if the user confirms.
     *
     * @see #doDiscardSession()
     */
    public void promptToDiscardSession() {
        ArrayList<Session> checkedSessions = mSessionListAdapter
                .getCheckedSessions();
        if (checkedSessions == null || checkedSessions.size() == 0) {
            Utils.errMsg(this, "There are no sessions to discard");
            return;
        }
        String msg = SessionManagerActivity.this.getString(
                R.string.session_delete_prompt, checkedSessions.size());
        new AlertDialog.Builder(SessionManagerActivity.this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.confirm)
                .setMessage(msg)
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog,
                                                int which) {
                                dialog.dismiss();
                                doDiscardSession();
                            }
                        }).setNegativeButton(R.string.cancel, null).show();
    }

    /**
     * Does the actual work of discarding the selected sessions.
     */
    public void doDiscardSession() {
        ArrayList<Session> checkedSessions = mSessionListAdapter
                .getCheckedSessions();
        if (checkedSessions == null || checkedSessions.size() == 0) {
            Utils.errMsg(this, "There are no sessions to discard");
            return;
        }
        long startDate = INVALID_DATE;
        for (Session session : checkedSessions) {
            startDate = session.getStartDate();
            mDbAdapter.deleteAllDataForStartDate(startDate);
        }
        refresh();
    }

    /**
     * Saves the database as a CSV file with a .txt extension.
     */
    private void saveDatabase() {
        BufferedWriter out = null;
        Cursor cursor = null;
        try {
            if (mDataDir == null) {
                Utils.errMsg(this, "Cannot determine directory for save");
                return;
            }
            String format = "yyyy-MM-dd-HHmmss";
            SimpleDateFormat df = new SimpleDateFormat(format, Locale.US);
            Date now = new Date();
            String fileName = String.format(SAVE_DATABASE_FILENAME_TEMPLATE,
                    df.format(now));
            File file = new File(mDataDir, fileName);
            FileWriter writer = new FileWriter(file);
            out = new BufferedWriter(writer);
            cursor = mDbAdapter.fetchAllData(null);
            int indexDate = cursor.getColumnIndex(COL_DATE);
            int indexStartDate = cursor.getColumnIndex(COL_START_DATE);
            int indexHr = cursor.getColumnIndex(COL_HR);
            int indexRr = cursor.getColumnIndex(COL_RR);
            // Loop over items
            cursor.moveToFirst();
            String rr, info = "";
            long dateNum, startDateNum;
            int hr;
            while (cursor.isAfterLast() == false) {
                dateNum = startDateNum = INVALID_DATE;
                hr = INVALID_INT;
                rr = " ";
                if (indexDate > -1) {
                    try {
                        dateNum = cursor.getLong(indexDate);
                    } catch (Exception ex) {
                        // Do nothing
                    }
                }
                if (indexStartDate > -1) {
                    try {
                        startDateNum = cursor.getLong(indexStartDate);
                    } catch (Exception ex) {
                        // Do nothing
                    }
                }
                if (indexHr > -1) {
                    try {
                        hr = cursor.getInt(indexHr);
                    } catch (Exception ex) {
                        // Do nothing
                    }
                }
                if (indexRr > -1) {
                    try {
                        rr = cursor.getString(indexRr);
                    } catch (Exception ex) {
                        // Do nothing
                    }
                    // Need to do this, or it isn't recognized as a token
                    if (rr.length() == 0) {
                        rr = " ";
                    }
                }
                info = String.format(Locale.US, "%d%s%d%s%d%s%s%s\n", dateNum,
                        SAVE_DATABASE_DELIM, startDateNum, SAVE_DATABASE_DELIM,
                        hr, SAVE_DATABASE_DELIM, rr, SAVE_DATABASE_DELIM);
                out.write(info);
                cursor.moveToNext();
            }
            Utils.infoMsg(this, "Wrote " + file.getPath());
        } catch (Exception ex) {
            Utils.excMsg(this, "Error saving database", ex);
        } finally {
            try {
                cursor.close();
            } catch (Exception ex) {
                // Do nothing
            }
            try {
                out.close();
            } catch (Exception ex) {
                // Do nothing
            }
        }
    }

    /**
     * Does the preliminary checking for restoring data, prompts if it is OK to
     * delete the current data, and call restoreData to actually do the delete
     * and restore.
     */
    private void checkRestoreDatabase() {
        if (mDataDir == null) {
            Utils.errMsg(this, "Cannot find data directory");
            return;
        }

        // Find the .txt files in the data directory
        final File[] files = mDataDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                if (file.isDirectory()) {
                    return false;
                }
                String name = file.getName();
                if (name.startsWith(SAVE_DATABASE_FILENAME_PREFIX)
                        && name.endsWith(SAVE_DATABASE_FILENAME_SUFFIX)) {
                    return true;
                }
                return false;
            }
        });
        if (files == null || files.length == 0) {
            Utils.errMsg(this,
                    "There are no saved database files in the data directory");
            return;
        }

        // Sort them by date with newest first
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File f1, File f2) {
                return Long.valueOf(f2.lastModified()).compareTo(
                        f1.lastModified());
            }
        });

        // Prompt for the file to use
        final CharSequence[] items = new CharSequence[files.length];
        for (int i = 0; i < files.length; i++) {
            items[i] = files[i].getName();
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getText(R.string.select_restore_file));
        builder.setSingleChoiceItems(items, 0,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, final int
                            item) {
                        dialog.dismiss();
                        if (item < 0 || item >= files.length) {
                            Utils.errMsg(SessionManagerActivity.this,
                                    "Invalid item");
                            return;
                        }
                        // Confirm the user wants to delete all the current data
                        new AlertDialog.Builder(SessionManagerActivity.this)
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .setTitle(R.string.confirm)
                                .setMessage(R.string.delete_prompt)
                                .setPositiveButton(R.string.ok,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(
                                                    DialogInterface dialog,
                                                    int which) {
                                                dialog.dismiss();
                                                restoreDatabase(files[item]);
                                            }

                                        })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    }
                });
        AlertDialog alert = builder.create();
        alert.show();
    }

    /**
     * Deletes the existing data without prompting and restores the new data.
     */
    private void restoreDatabase(File file) {
        if (!file.exists()) {
            Utils.errMsg(this, "Cannot find:\n" + file.getPath());
            return;
        }
        if (mRestoreTask != null) {
            // Don't do anything if we are updating
            Log.d(TAG,
                    this.getClass().getSimpleName()
                            + ": restoreDatabase: restoreTask is not null for "
                            + file.getName());
            return;
        }

        mRestoreTask = new RestoreTask(file);
        mRestoreTask.execute();
    }

    /**
     * Refreshes the sessions by recreating the list adapter.
     */
    public void refresh() {
        // Initialize the list view adapter
        mSessionListAdapter = new SessionListAdapter();
        setListAdapter(mSessionListAdapter);
    }

    /**
     * Class to handle getting the bitmap from the web using a progress bar that
     * can be cancelled.<br>
     * <br>
     * Call with <b>Bitmap bitmap = new MyUpdateTask().execute(String)<b>
     */
    private class RestoreTask extends AsyncTask<Void, Void, Boolean> {
        private ProgressDialog dialog;
        private File file;
        private int mErrors;
        private int mLineNumber;
        private String mExceptionMsg;

        public RestoreTask(File file) {
            super();
            this.file = file;
        }

        @Override
        protected void onPreExecute() {
            dialog = new ProgressDialog(SessionManagerActivity.this);
            dialog.setMessage(getString(R.string
                    .restoring_database_progress_text));
            dialog.setCancelable(false);
            dialog.setIndeterminate(true);
            dialog.show();
        }

        @Override
        protected Boolean doInBackground(Void... dummy) {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            BufferedReader in = null;
            try {
                // Delete all the data and recreate the table
                mDbAdapter.recreateDataTable();

                // Read the file and get the data to restore
                in = new BufferedReader(new FileReader(file));
                String rr;
                long dateNum, startDateNum;
                int hr;
                String[] tokens = null;
                String line = null;
                while ((line = in.readLine()) != null) {
                    dateNum = startDateNum = INVALID_DATE;
                    hr = INVALID_INT;
                    rr = " ";
                    mLineNumber++;
                    tokens = line.trim().split(SAVE_DATABASE_DELIM);
                    // Skip blank lines
                    if (line.trim().length() == 0) {
                        continue;
                    }
                    // Skip lines starting with #
                    if (tokens[0].trim().startsWith("#")) {
                        continue;
                    }
                    hr = 0;
                    rr = "";
                    if (tokens.length < 4) {
                        // Utils.errMsg(this, "Found " + tokens.length
                        // + " tokens for line " + lineNum
                        // + "\nShould be 5 or more tokens");
                        mErrors++;
                        Log.d(TAG, "tokens.length=" + tokens.length
                                + " @ line " + mLineNumber);
                        Log.d(TAG, line);
                        continue;
                    }
                    try {
                        dateNum = Long.parseLong(tokens[0]);
                    } catch (Exception ex) {
                        Log.d(TAG, "Long.parseLong failed for dateNum @ line "
                                + mLineNumber);
                    }
                    try {
                        startDateNum = Long.parseLong(tokens[1]);
                    } catch (Exception ex) {
                        Log.d(TAG,
                                "Long.parseLong failed for startDateNum @ line "
                                        + mLineNumber);
                    }
                    try {
                        hr = Integer.parseInt(tokens[2]);
                    } catch (Exception ex) {
                        Log.d(TAG, "Integer.parseInt failed for hr @ line "
                                + mLineNumber);
                    }
                    rr = tokens[3].trim();
                    // Write the row
                    long id = mDbAdapter.createData(dateNum, startDateNum, hr,
                            rr);
                    if (id < 0) {
                        mErrors++;
                    }
                }
            } catch (Exception ex) {
                mExceptionMsg = "Got Exception restoring at line "
                        + mLineNumber + "\n" + ex.getMessage();
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (Exception ex) {
                    // Do nothing
                }
            }
            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (mExceptionMsg != null) {

            }
            Log.d(TAG, this.getClass().getSimpleName()
                    + ": onPostExecute: result=" + result);
            if (dialog != null) {
                dialog.dismiss();
            }
            mRestoreTask = null;
            String info;
            if (mErrors == 0) {
                info = "Restored " + mLineNumber + " lines from "
                        + file.getPath();
            } else {
                info = "Got " + mErrors + " errors processing " + mLineNumber
                        + " lines from " + file.getPath();
                if (mExceptionMsg != null) {
                    info += "\n" + mExceptionMsg;
                }
            }
            Utils.infoMsg(SessionManagerActivity.this, info);
            refresh();
        }
    }

    /**
     * Sets all the sessions to checked or not.
     *
     * @param checked
     */
    public void setAllSessionsChecked(Boolean checked) {
        ArrayList<Session> sessions = mSessionListAdapter.getSessions();
        CheckBox cb = null;
        for (Session session : sessions) {
            session.setChecked(checked);
            cb = session.getCheckBox();
            if (cb != null) {
                cb.setChecked(checked);
            }
        }
    }

    /**
     * Creates a session name form the given date.
     *
     * @param date
     * @return
     */
    public static String sessionNameFromDate(long date) {
        return SESSION_NAME_PREFIX + fileNameFormatter.format(new Date(date));
    }

    // Adapter for holding sessions
    private class SessionListAdapter extends BaseAdapter {
        private ArrayList<Session> mSessions;
        private LayoutInflater mInflator;

        public SessionListAdapter() {
            super();
            mSessions = new ArrayList<Session>();
            mInflator = SessionManagerActivity.this.getLayoutInflater();
            Cursor cursor = null;
            int nItems = 0;
            try {
                if (mDbAdapter != null) {
                    cursor = mDbAdapter.fetchAllSessionStartEndData();
                    // // DEBUG
                    // Log.d(TAG,
                    // this.getClass().getSimpleName()
                    // + ": SessionListAdapter: " + "rows="
                    // + cursor.getCount() + " cols="
                    // + cursor.getColumnCount());
                    // String[] colNames = cursor.getColumnNames();
                    // for (String colName : colNames) {
                    // Log.d(TAG, "  " + colName);
                    // }

                    int indexStartDate = cursor
                            .getColumnIndexOrThrow(COL_START_DATE);
                    int indexEndDate = cursor
                            .getColumnIndexOrThrow(COL_END_DATE);
                    // int indexTmp = cursor.getColumnIndexOrThrow(COL_TMP);

                    // Loop over items
                    cursor.moveToFirst();
                    long startDate = INVALID_DATE;
                    long endDate = INVALID_DATE;
                    String name;
                    while (cursor.isAfterLast() == false) {
                        nItems++;
                        startDate = cursor.getLong(indexStartDate);
                        endDate = cursor.getLong(indexEndDate);

                        // // DEBUG
                        // double duration = endDate - startDate;
                        // int durationHours = (int) (duration / 3600000.);
                        // int durationMin = (int) (duration / 60000.)
                        // - durationHours * 60;
                        // int durationSec = (int) (duration / 1000.)
                        // - durationHours * 3600 - durationMin * 60;
                        // Log.d(TAG, "duration: " + durationHours + " hr "
                        // + durationMin + " min " + +durationSec + " sec");
                        // name = "Temporary Session ";

                        // String tempStr = cursor.getString(indexTmp);
                        // temp = tempStr.equals("1");
                        // name = "Session ";
                        // if (temp) {
                        // name = "Temporary Session ";
                        // }
                        name = sessionNameFromDate(startDate);
                        addSession(new Session(name, startDate, endDate));
                        cursor.moveToNext();
                    }
                }
                cursor.close();
            } catch (Exception ex) {
                Utils.excMsg(SessionManagerActivity.this,
                        "Error getting sessions", ex);
            } finally {
                try {
                    cursor.close();
                } catch (Exception ex) {
                    // Do nothing
                }
            }
            Log.d(TAG, "Session list created with " + nItems + " items");
        }

        public void addSession(Session session) {
            if (!mSessions.contains(session)) {
                mSessions.add(session);
            }
        }

        public Session getSession(int position) {
            return mSessions.get(position);
        }

        public void clear() {
            mSessions.clear();
        }

        @Override
        public int getCount() {
            return mSessions.size();
        }

        @Override
        public Object getItem(int i) {
            return mSessions.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            // // DEBUG
            // Log.d(TAG, "getView: " + i);
            ViewHolder viewHolder = null;
            // General ListView optimization code.
            if (view == null) {
                view = mInflator.inflate(R.layout.listitem_session, viewGroup,
                        false);
                viewHolder = new ViewHolder();
                viewHolder.sessionCheckbox = (CheckBox) view
                        .findViewById(R.id.session_checkbox);
                viewHolder.sessionStart = (TextView) view
                        .findViewById(R.id.session_start);
                viewHolder.sessionDuration = (TextView) view
                        .findViewById(R.id.session_end);
                view.setTag(viewHolder);

                viewHolder.sessionCheckbox
                        .setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                CheckBox cb = (CheckBox) v;
                                Session session = (Session) cb.getTag();
                                boolean checked = cb.isChecked();
                                session.setChecked(checked);
                                // // DEBUG
                                // Log.d(TAG,
                                // "sessionCheckbox.onClickListener: "
                                // + session.getName() + " "
                                // + session.isChecked());
                            }
                        });
            } else {
                viewHolder = (ViewHolder) view.getTag();
            }

            Session session = mSessions.get(i);
            // Set the name
            final String sessionName = session.getName();
            if (sessionName != null && sessionName.length() > 0) {
                viewHolder.sessionCheckbox.setText(sessionName);
                Date startDate = new Date(session.getStartDate());
                String startStr = mediumFormatter.format(startDate);
                double duration = session.getDuration();
                int durationDays = (int) (duration / (3600000. * 24));

                int durationHours = (int) (duration / 3600000.) - durationDays;
                int durationMin = (int) (duration / 60000.) - durationHours
                        * 60;
                int durationSec = (int) (duration / 1000.) - durationHours
                        * 3600 - durationMin * 60;
                String durString = "";
                if (durationDays > 0) {
                    durString += durationDays + " day ";
                }
                if (durationHours > 0) {
                    durString += durationHours + " hr ";
                }
                if (durationMin > 0) {
                    durString += durationMin + " min ";
                }
                durString += durationSec + " sec";
                viewHolder.sessionStart.setText(startStr);
                viewHolder.sessionDuration.setText(durString);
            } else {
                viewHolder.sessionCheckbox.setText(R.string.unknown_device);
                viewHolder.sessionStart.setText("");
                viewHolder.sessionDuration.setText("");
            }
            // Set the tag for the CheckBox to the session and set its state
            viewHolder.sessionCheckbox.setChecked(session.isChecked());
            viewHolder.sessionCheckbox.setTag(session);
            // And set the associated checkBox for the session
            session.setCheckBox(viewHolder.sessionCheckbox);
            return view;
        }

        /**
         * Get a list of checked sessions.
         *
         * @return
         */
        public ArrayList<Session> getSessions() {
            return mSessions;
        }

        /**
         * Get a list of checked sessions.
         *
         * @return
         */
        public ArrayList<Session> getCheckedSessions() {
            ArrayList<Session> checkedSessions = new ArrayList<Session>();
            for (Session session : mSessions) {
                if (session.isChecked()) {
                    checkedSessions.add(session);
                }
            }
            return checkedSessions;
        }

    }

    /**
     * Convenience class for managing views for a ListView row.
     */
    static class ViewHolder {
        CheckBox sessionCheckbox;
        TextView sessionStart;
        TextView sessionDuration;
    }

}

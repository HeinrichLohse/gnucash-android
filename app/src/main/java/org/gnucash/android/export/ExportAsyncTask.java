/*
 * Copyright (c) 2013 - 2015 Ngewi Fet <ngewif@gmail.com>
 * Copyright (c) 2014 Yongxin Wang <fefe.wyx@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gnucash.android.export;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.dropbox.sync.android.DbxAccountManager;
import com.dropbox.sync.android.DbxException;
import com.dropbox.sync.android.DbxFile;
import com.dropbox.sync.android.DbxFileSystem;
import com.dropbox.sync.android.DbxPath;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveApi;
import com.google.android.gms.drive.DriveContents;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveId;
import com.google.android.gms.drive.MetadataChangeSet;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.OwnCloudClientFactory;
import com.owncloud.android.lib.common.OwnCloudCredentialsFactory;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.resources.files.CreateRemoteFolderOperation;
import com.owncloud.android.lib.resources.files.FileUtils;
import com.owncloud.android.lib.resources.files.UploadRemoteFileOperation;

import org.gnucash.android.R;
import org.gnucash.android.app.GnuCashApplication;
import org.gnucash.android.db.adapter.AccountsDbAdapter;
import org.gnucash.android.db.adapter.DatabaseAdapter;
import org.gnucash.android.db.adapter.SplitsDbAdapter;
import org.gnucash.android.db.adapter.TransactionsDbAdapter;
import org.gnucash.android.export.ofx.OfxExporter;
import org.gnucash.android.export.qif.QifExporter;
import org.gnucash.android.export.xml.GncXmlExporter;
import org.gnucash.android.model.Transaction;
import org.gnucash.android.ui.account.AccountsActivity;
import org.gnucash.android.ui.account.AccountsListFragment;
import org.gnucash.android.ui.settings.BackupPreferenceFragment;
import org.gnucash.android.ui.transaction.TransactionsActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Asynchronous task for exporting transactions.
 *
 * @author Ngewi Fet <ngewif@gmail.com>
 */
public class ExportAsyncTask extends AsyncTask<ExportParams, Void, Boolean> {

    /**
     * App context
     */
    private final Context mContext;

    private ProgressDialog mProgressDialog;

    private SQLiteDatabase mDb;

    /**
     * Log tag
     */
    public static final String TAG = "ExportAsyncTask";

    /**
     * Export parameters
     */
    private ExportParams mExportParams;

    // File paths generated by the exporter
    private List<String> mExportedFiles;

    private Exporter mExporter;

    public ExportAsyncTask(Context context, SQLiteDatabase db){
        this.mContext = context;
        this.mDb = db;
    }

    @Override
    @TargetApi(11)
    protected void onPreExecute() {
        super.onPreExecute();
        if (mContext instanceof Activity) {
            mProgressDialog = new ProgressDialog(mContext);
            mProgressDialog.setTitle(R.string.title_progress_exporting_transactions);
            mProgressDialog.setIndeterminate(true);
            mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.HONEYCOMB) {
                mProgressDialog.setProgressNumberFormat(null);
                mProgressDialog.setProgressPercentFormat(null);
            }
            mProgressDialog.show();
        }
    }

    /**
     * Generates the appropriate exported transactions file for the given parameters
     * @param params Export parameters
     * @return <code>true</code> if export was successful, <code>false</code> otherwise
     */
    @Override
    protected Boolean doInBackground(ExportParams... params) {
        mExportParams = params[0];

        switch (mExportParams.getExportFormat()) {
                case QIF:
                    mExporter = new QifExporter(mExportParams);
                    break;

                case OFX:
                    mExporter = new OfxExporter(mExportParams);
                    break;

                case XML:
                default:
                    mExporter = new GncXmlExporter(mExportParams);
                    break;
        }

        try {
            // FIXME: detect if there aren't transactions to export and inform the user
            mExportedFiles = mExporter.generateExport();
        } catch (final Exception e) {
            Log.e(TAG, "Error exporting: " + e.getMessage());
            Crashlytics.logException(e);
            e.printStackTrace();
            if (mContext instanceof Activity) {
                ((Activity)mContext).runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(mContext,
                                mContext.getString(R.string.toast_export_error, mExportParams.getExportFormat().name())
                                + "\n" + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
            return false;
        }

        switch (mExportParams.getExportTarget()) {
            case SHARING:
                List<String> sdCardExportedFiles = moveExportToSDCard();
                shareFiles(sdCardExportedFiles);
                return true;

            case DROPBOX:
                moveExportToDropbox();
                return true;

            case GOOGLE_DRIVE:
                moveExportToGoogleDrive();
                return true;

            case OWNCLOUD:
                moveExportToOwnCloud();
                return true;

            case SD_CARD:
                moveExportToSDCard();
                return true;
        }

        return false;
    }

    /**
     * Transmits the exported transactions to the designated location, either SD card or third-party application
     * Finishes the activity if the export was starting  in the context of an activity
     * @param exportResult Result of background export execution
     */
    @Override
    protected void onPostExecute(Boolean exportResult) {
        if (mContext instanceof Activity) {
            if (!exportResult) {
                Toast.makeText(mContext,
                        mContext.getString(R.string.toast_export_error, mExportParams.getExportFormat().name()),
                        Toast.LENGTH_LONG).show();
                return;
            } else {
                String targetLocation;
                switch (mExportParams.getExportTarget()){
                    case SD_CARD:
                        targetLocation = "SD card";
                        break;
                    case DROPBOX:
                        targetLocation = "DropBox -> Apps -> GnuCash";
                        break;
                    case GOOGLE_DRIVE:
                        targetLocation = "Google Drive -> " + mContext.getString(R.string.app_name);
                        break;
                    case OWNCLOUD:
                        targetLocation = mContext.getSharedPreferences(
                                mContext.getString(R.string.owncloud_pref),
                                Context.MODE_PRIVATE).getBoolean(
                                mContext.getString(R.string.owncloud_sync), false) ?

                                "ownCloud -> " +
                                mContext.getSharedPreferences(
                                        mContext.getString(R.string.owncloud_pref),
                                        Context.MODE_PRIVATE).getString(
                                        mContext.getString(R.string.key_owncloud_dir), null) :
                                "ownCloud sync not enabled";
                        break;
                    default:
                        targetLocation = "external service";
                }
                Toast.makeText(mContext,
                        String.format(mContext.getString(R.string.toast_exported_to), targetLocation),
                        Toast.LENGTH_LONG).show();
            }
        }

        if (mExportParams.shouldDeleteTransactionsAfterExport()) {
            Log.i(TAG, "Backup and deleting transactions after export");
            backupAndDeleteTransactions();

            //now refresh the respective views
            if (mContext instanceof AccountsActivity){
                AccountsListFragment fragment = ((AccountsActivity) mContext).getCurrentAccountListFragment();
                if (fragment != null)
                    fragment.refresh();
            }
            if (mContext instanceof TransactionsActivity){
                ((TransactionsActivity) mContext).refresh();
            }
        }

        if (mContext instanceof Activity) {
            if (mProgressDialog != null && mProgressDialog.isShowing())
                mProgressDialog.dismiss();
            ((Activity) mContext).finish();
        }
    }

    private void moveExportToGoogleDrive(){
        Log.i(TAG, "Moving exported file to Google Drive");
        final GoogleApiClient googleApiClient = BackupPreferenceFragment.getGoogleApiClient(GnuCashApplication.getAppContext());
        googleApiClient.blockingConnect();
        final ResultCallback<DriveFolder.DriveFileResult> fileCallback = new
                ResultCallback<DriveFolder.DriveFileResult>() {
                    @Override
                    public void onResult(DriveFolder.DriveFileResult result) {
                        if (!result.getStatus().isSuccess())
                            Log.e(TAG, "Error while trying to sync to Google Drive");
                        else
                            Log.i(TAG, "Created a file with content: " + result.getDriveFile().getDriveId());
                    }
                };

        Drive.DriveApi.newDriveContents(googleApiClient).setResultCallback(new ResultCallback<DriveApi.DriveContentsResult>() {
            @Override
            public void onResult(DriveApi.DriveContentsResult result) {
                if (!result.getStatus().isSuccess()) {
                    Log.e(TAG, "Error while trying to create new file contents");
                    return;
                }
                final DriveContents driveContents = result.getDriveContents();
                try {
                    // write content to DriveContents
                    OutputStream outputStream = driveContents.getOutputStream();
                    for (String exportedFilePath : mExportedFiles) {
                        File exportedFile = new File(exportedFilePath);
                        FileInputStream fileInputStream = new FileInputStream(exportedFile);
                        byte[] buffer = new byte[1024];
                        int count;

                        while ((count = fileInputStream.read(buffer)) >= 0) {
                            outputStream.write(buffer, 0, count);
                        }
                        fileInputStream.close();
                        outputStream.flush();
                        exportedFile.delete();

                        MetadataChangeSet changeSet = new MetadataChangeSet.Builder()
                                .setTitle(exportedFile.getName())
                                .setMimeType(mExporter.getExportMimeType())
                                .build();

                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
                        String folderId = sharedPreferences.getString(mContext.getString(R.string.key_google_drive_app_folder_id), "");
                        DriveFolder folder = Drive.DriveApi.getFolder(googleApiClient, DriveId.decodeFromString(folderId));
                        // create a file on root folder
                        folder.createFile(googleApiClient, changeSet, driveContents)
                                .setResultCallback(fileCallback);
                    }

                } catch (IOException e) {
                    Crashlytics.logException(e);
                    Log.e(TAG, e.getMessage());
                }
            }
        });
    }

    private void moveExportToDropbox() {
        Log.i(TAG, "Copying exported file to DropBox");
        String dropboxAppKey = mContext.getString(R.string.dropbox_app_key, BackupPreferenceFragment.DROPBOX_APP_KEY);
        String dropboxAppSecret = mContext.getString(R.string.dropbox_app_secret, BackupPreferenceFragment.DROPBOX_APP_SECRET);
        DbxAccountManager mDbxAcctMgr = DbxAccountManager.getInstance(mContext.getApplicationContext(),
                dropboxAppKey, dropboxAppSecret);
        DbxFile dbExportFile = null;
        try {
            DbxFileSystem dbxFileSystem = DbxFileSystem.forAccount(mDbxAcctMgr.getLinkedAccount());
            for (String exportedFilePath : mExportedFiles) {
                File exportedFile = new File(exportedFilePath);
                dbExportFile = dbxFileSystem.create(new DbxPath(exportedFile.getName()));
                dbExportFile.writeFromExistingFile(exportedFile, false);
                exportedFile.delete();
            }
        } catch (DbxException.Unauthorized unauthorized) {
            Crashlytics.logException(unauthorized);
            Log.e(TAG, unauthorized.getMessage());
            throw new Exporter.ExporterException(mExportParams);
        } catch (IOException e) {
            Crashlytics.logException(e);
            Log.e(TAG, e.getMessage());
        } finally {
            if (dbExportFile != null) {
                dbExportFile.close();
            }
        }
    }

    private void moveExportToOwnCloud() {
        Log.i(TAG, "Copying exported file to ownCloud");

        SharedPreferences mPrefs = mContext.getSharedPreferences(mContext.getString(R.string.owncloud_pref), Context.MODE_PRIVATE);

        Boolean mOC_sync = mPrefs.getBoolean(mContext.getString(R.string.owncloud_sync), false);

        if(!mOC_sync){
            Log.e(TAG, "ownCloud not enabled.");
            return;
        }

        String mOC_server = mPrefs.getString(mContext.getString(R.string.key_owncloud_server), null);
        String mOC_username = mPrefs.getString(mContext.getString(R.string.key_owncloud_username), null);
        String mOC_password = mPrefs.getString(mContext.getString(R.string.key_owncloud_password), null);
        String mOC_dir = mPrefs.getString(mContext.getString(R.string.key_owncloud_dir), null);

        Uri serverUri = Uri.parse(mOC_server);
        OwnCloudClient mClient = OwnCloudClientFactory.createOwnCloudClient(serverUri, this.mContext, true);
        mClient.setCredentials(
                OwnCloudCredentialsFactory.newBasicCredentials(mOC_username, mOC_password)
        );

        if (mOC_dir.length() != 0) {
            RemoteOperationResult dirResult = new CreateRemoteFolderOperation(
                    mOC_dir, true).execute(mClient);
            if (!dirResult.isSuccess())
                Log.e(TAG, dirResult.getLogMessage(), dirResult.getException());
        }
        for (String exportedFilePath : mExportedFiles) {
            String remotePath = mOC_dir + FileUtils.PATH_SEPARATOR + stripPathPart(exportedFilePath);
            String mimeType = mExporter.getExportMimeType();

            RemoteOperationResult result = new UploadRemoteFileOperation(
                    exportedFilePath, remotePath, mimeType).execute(mClient);

            if (!result.isSuccess())
                Log.e(TAG, result.getLogMessage(), result.getException());
            else {
                new File(exportedFilePath).delete();
            }
        }
    }

    /**
     * Moves the exported files from the internal storage where they are generated to
     * external storage, which is accessible to the user.
     * @return The list of files moved to the SD card.
     */
    private List<String> moveExportToSDCard() {
        Log.i(TAG, "Moving exported file to external storage");
        new File(Exporter.getExportFolderPath());
        List<String> dstFiles = new ArrayList<>();

        for (String src: mExportedFiles) {
            String dst = Exporter.getExportFolderPath() + stripPathPart(src);
            try {
                moveFile(src, dst);
                dstFiles.add(dst);
            } catch (IOException e) {
                Crashlytics.logException(e);
                Log.e(TAG, e.getMessage());
                throw new Exporter.ExporterException(mExportParams, e);
            }
        }

        return dstFiles;
    }

    // "/some/path/filename.ext" -> "filename.ext"
    private String stripPathPart(String fullPathName) {
        return (new File(fullPathName)).getName();
    }

    /**
     * Backups of the database, saves opening balances (if necessary)
     * and deletes all non-template transactions in the database.
     */
    private void backupAndDeleteTransactions(){
        GncXmlExporter.createBackup(); //create backup before deleting everything
        List<Transaction> openingBalances = new ArrayList<>();
        boolean preserveOpeningBalances = GnuCashApplication.shouldSaveOpeningBalances(false);

        TransactionsDbAdapter transactionsDbAdapter = new TransactionsDbAdapter(mDb, new SplitsDbAdapter(mDb));
        if (preserveOpeningBalances) {
            openingBalances = new AccountsDbAdapter(mDb, transactionsDbAdapter).getAllOpeningBalanceTransactions();
        }
        transactionsDbAdapter.deleteAllNonTemplateTransactions();

        if (preserveOpeningBalances) {
            transactionsDbAdapter.bulkAddRecords(openingBalances, DatabaseAdapter.UpdateMethod.insert);
        }
    }

    /**
     * Starts an intent chooser to allow the user to select an activity to receive
     * the exported files.
     * @param paths list of full paths of the files to send to the activity.
     */
    private void shareFiles(List<String> paths) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.setType("text/xml");

        ArrayList<Uri> exportFiles = convertFilePathsToUris(paths);
//        shareIntent.putExtra(Intent.EXTRA_STREAM, exportFiles);
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, exportFiles);

        shareIntent.putExtra(Intent.EXTRA_SUBJECT, mContext.getString(R.string.title_export_email,
                mExportParams.getExportFormat().name()));

        String defaultEmail = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(mContext.getString(R.string.key_default_export_email), null);
        if (defaultEmail != null && defaultEmail.trim().length() > 0)
            shareIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{defaultEmail});

        SimpleDateFormat formatter = (SimpleDateFormat) SimpleDateFormat.getDateTimeInstance();
        ArrayList<CharSequence> extraText = new ArrayList<>();
        extraText.add(mContext.getString(R.string.description_export_email)
                + " " + formatter.format(new Date(System.currentTimeMillis())));
        shareIntent.putExtra(Intent.EXTRA_TEXT, extraText);

        if (mContext instanceof Activity) {
            List<ResolveInfo> activities = mContext.getPackageManager().queryIntentActivities(shareIntent, 0);
            if (activities != null && !activities.isEmpty()) {
                mContext.startActivity(Intent.createChooser(shareIntent,
                        mContext.getString(R.string.title_select_export_destination)));
            } else {
                Toast.makeText(mContext, R.string.toast_no_compatible_apps_to_receive_export,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Convert file paths to URIs by adding the file// prefix
     * <p>e.g. /some/path/file.ext --> file:///some/path/file.ext</p>
     * @param paths List of file paths to convert
     * @return List of file URIs
     */
    @NonNull
    private ArrayList<Uri> convertFilePathsToUris(List<String> paths) {
        ArrayList<Uri> exportFiles = new ArrayList<>();

        for (String path : paths) {
            File file = new File(path);
            file.setReadable(true, false);
            exportFiles.add(Uri.fromFile(file));
//            exportFiles.add(Uri.parse("file://" + file));
        }
        return exportFiles;
    }

    /**
     * Moves a file from <code>src</code> to <code>dst</code>
     * @param src Absolute path to the source file
     * @param dst Absolute path to the destination file
     * @throws IOException if the file could not be moved.
     */
    public void moveFile(String src, String dst) throws IOException {
        File srcFile = new File(src);
        File dstFile = new File(dst);
        FileChannel inChannel = new FileInputStream(srcFile).getChannel();
        FileChannel outChannel = new FileOutputStream(dstFile).getChannel();
        try {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        } finally {
            if (inChannel != null)
                inChannel.close();
            outChannel.close();
        }
        srcFile.delete();
    }

}

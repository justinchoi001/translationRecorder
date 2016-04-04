package wycliffeassociates.recordingapp.FilesPage;

import android.app.Activity;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Hashtable;
import wycliffeassociates.recordingapp.AudioInfo;
import wycliffeassociates.recordingapp.FilesPage.Export.Export;
import wycliffeassociates.recordingapp.FilesPage.Export.ExportTaskFragment;
import wycliffeassociates.recordingapp.SettingsPage.InternsPreferencesManager;
import wycliffeassociates.recordingapp.R;
import wycliffeassociates.recordingapp.FileManagerUtils.FileItem;

public class AudioFiles extends Activity implements FragmentShareDialog.ExportDelegator, Export.ProgressUpdateCallback {

    private CheckBox btnCheckAll;
    private Menu mMenu;
    private ListView audioFileView;
    private TextView mDirectoryPath;
    private ImageButton mPreviousDir;
    private static String currentDir;
    private File file[];

    private ArrayList<FileItem> fileItemList;
    private ArrayList<FileItem> tempItemList;
    static ArrayList<String> exportList;
    private ProgressDialog mPd;
    private ExportTaskFragment mExportTaskFragment;
    private final String TAG_EXPORT_TASK_FRAGMENT = "export_task_fragment";
    private final String STATE_EXPORTING = "was_exporting";
    private final String STATE_ZIPPING = "was_zipping";
    private static final String TOP_LIST_ITEM = "top_list_item";
    private static final String TOP_LIST_ITEM_OFFSET = "top_list_item_offset";
    private final String STATE_PROGRESS = "upload_progress";
    private boolean checkAll = true;
    private volatile int mProgress = 0;
    private volatile boolean mZipping = false;
    private volatile boolean mExporting = false;

    // 0: Z-A
    // 1: A-Z
    // 2: 0:00 - 9:99
    // 3: 9:99 - 0:00
    // 4: Oldest First
    // 5: Recent First
    int sort = 5;
    public AudioFilesAdapter adapter;
    Hashtable<Date, String> audioHash;
    InternsPreferencesManager oldPref;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.audio_list);

        // Hide the fragment to start with
        hideFragment(R.id.file_actions);

        // Pull file directory and sorting Preferences
        oldPref = new InternsPreferencesManager(this);
        currentDir = (String) oldPref.getPreferences("fileDirectory");
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        if(pref.getString("fileDirectory", null) == null){
            pref.edit().putString("fileDirectory", Environment.getExternalStorageDirectory().getAbsolutePath().toString() + "/" + this.getString(R.string.folder_name)).commit();
        }
        currentDir = pref.getString("fileDirectory", currentDir);
        AudioInfo.fileDir = currentDir;
        sort = (int) oldPref.getPreferences("displaySort");

        audioFileView = (ListView) findViewById(R.id.main_content);
        btnCheckAll = (CheckBox)findViewById(R.id.btnCheckAll);
        mPreviousDir = (ImageButton)findViewById(R.id.btnPreviousDir);
        mDirectoryPath = (TextView)findViewById(R.id.pathView);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mDirectoryPath.setText(currentDir);
                mDirectoryPath.invalidate();
            }
        });



        // Cleanup any leftover visualization files
        removeUnusedVisualizationFiles(currentDir);

        //get files in the directory
        File f = new File(currentDir);
        file = f.listFiles();
        // No files
        if (file == null) {
            Toast.makeText(AudioFiles.this, "No Audio Files in Folder", Toast.LENGTH_SHORT).show();
        // Get audio files
        } else {
            initFiles(file);
        }

        setButtonHandlers();

        FragmentManager fm = getFragmentManager();
        mExportTaskFragment = (ExportTaskFragment) fm.findFragmentByTag(TAG_EXPORT_TASK_FRAGMENT);

        if(savedInstanceState != null) {
            mZipping = savedInstanceState.getBoolean(STATE_ZIPPING, false);
            mExporting = savedInstanceState.getBoolean(STATE_EXPORTING, false);
            mProgress = savedInstanceState.getInt(STATE_PROGRESS, 0);
        }
        //check if fragment was retained from a screen rotation
        if(mExportTaskFragment == null){
            mExportTaskFragment = new ExportTaskFragment();
            fm.beginTransaction().add(mExportTaskFragment, TAG_EXPORT_TASK_FRAGMENT).commit();
            fm.executePendingTransactions();
        } else {
            if(mZipping){
                zipProgress(mProgress);
            } else if(mExporting){
                exportProgress(mProgress);
            }
        }
    }

    public void exportProgress(int progress){
        mPd = new ProgressDialog(this);
        mPd.setTitle("Uploading...");
        mPd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mPd.setProgress(progress);
        mPd.setCancelable(false);
        mPd.show();
    }

    public void zipProgress(int progress){
        mPd = new ProgressDialog(this);
        mPd.setTitle("Packaging files to export.");
        mPd.setMessage("Please wait...");
        mPd.setProgress(progress);
        mPd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mPd.setCancelable(false);
        mPd.show();
    }

    public void dismissProgress(){
        mPd.dismiss();
    }

    public void incrementProgress(int progress){
        mPd.incrementProgressBy(progress);
    }

    public void setUploadProgress(int progress){
        mPd.setProgress(progress);
    }

    public void showProgress(boolean mode){
        if(mode == true){
            zipProgress(0);
        } else {
            exportProgress(0);
        }
    }

    @Override
    public void setZipping(boolean zipping){
        mZipping = zipping;
    }

    @Override
    public void setExporting(boolean exporting){
        mExporting = exporting;
    }

    @Override
    public void onDestroy(){
        super.onDestroy();
        if(mPd != null && mPd.isShowing()){
            mPd.dismiss();
            mPd = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle savedInstanceState){
        super.onSaveInstanceState(savedInstanceState);
        if(mPd != null) {
            savedInstanceState.putInt(STATE_PROGRESS, mPd.getProgress());
        }
        savedInstanceState.putBoolean(STATE_EXPORTING, mExporting);
        savedInstanceState.putBoolean(STATE_ZIPPING, mZipping);

        // Remember the scroll position and offset of the list
        // From: http://stackoverflow.com/a/3035521
        int offset = 0;
        View v = audioFileView.getChildAt(0);
        if (v != null) {
            offset = v.getTop() - audioFileView.getPaddingTop();
        }
        savedInstanceState.putInt(TOP_LIST_ITEM, audioFileView.getFirstVisiblePosition());
        savedInstanceState.putInt(TOP_LIST_ITEM_OFFSET, offset);
    }

    @Override
    protected void onRestoreInstanceState(final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        // Restore the scroll position and offset of the top list item
        // From: http://stackoverflow.com/a/3035521
        audioFileView.post(new Runnable() {
            @Override
            public void run() {
                int index = savedInstanceState.getInt(TOP_LIST_ITEM);
                int offset = savedInstanceState.getInt(TOP_LIST_ITEM_OFFSET);
                audioFileView.setSelectionFromTop(index, offset);
            }
        });
    }

    private void initFiles(File[] file){
        // Initialization
        fileItemList = new ArrayList<FileItem>();
        tempItemList = new ArrayList<FileItem>();
        audioHash = new Hashtable<Date, String>();

        for (int i = 0; i < file.length; i++) {
            int len = file[i].getName().length();
            if (len > 3) {
                String sub = file[i].getName().substring(len - 4);
                if (sub.equalsIgnoreCase(".3gp") || sub.equalsIgnoreCase(".wav")
                        || sub.equalsIgnoreCase(".mp3")) {
                    // Add file names
                    Date lastModDate = new Date(file[i].lastModified());
                    File tFile = new File(currentDir + "/" + file[i].getName());
                    long time = (((tFile.length() - 44) / 2) / 44100);
                    //create an Audio Item
                    tempItemList.add(new FileItem(file[i].getName(), lastModDate, (int) time, FileItem.FILE));
                }
            } else if(file[i].isDirectory()) {
                Date lastModDate = new Date(file[i].lastModified());
                //create an Audio Item
                tempItemList.add(new FileItem(file[i].getName(), lastModDate, 0, FileItem.DIRECTORY));
            }
            generateAdapterView(tempItemList, sort);
        }
    }

    private void setButtonHandlers() {
        findViewById(R.id.btnCheckAll).setOnClickListener(btnClick);
        findViewById(R.id.btnSortName).setOnClickListener(btnClick);
        findViewById(R.id.btnSortDuration).setOnClickListener(btnClick);
        findViewById(R.id.btnSortDate).setOnClickListener(btnClick);
        findViewById(R.id.btnPreviousDir).setOnClickListener(btnClick);
    }

    private View.OnClickListener btnClick = new View.OnClickListener() {

        @Override
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.btnCheckAll: {
                    checkAll();
                    break;
                }
                case R.id.btnSortName: {
                    sortName();
                    break;
                }
                case R.id.btnSortDuration: {
                    sortDuration();
                    break;
                }
                case R.id.btnSortDate: {
                    sortDate();
                    break;
                }
                case R.id.btnPreviousDir: {
                    backOneLevel();
                    break;
                }
            }
        }
    };

    private void backOneLevel(){
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        String path = pref.getString("fileDirectory", "");
        path = path.substring(0, path.lastIndexOf("/"));
        pref.edit().putString("fileDirectory", path).commit();
        finish();
        startActivity(getIntent());
    }

    private void checkAll(){
        if (file != null) {
            for (int i = 0; i < audioFileView.getCount(); i++) {
                adapter.checkBoxState[i] = checkAll;
                adapter.notifyDataSetChanged();
            }
            if (!checkAll) {
                exportList = new ArrayList<String>();
                if(adapter != null && adapter.checkBoxState != null){
                    Arrays.fill(adapter.checkBoxState, Boolean.FALSE);
                    adapter.notifyDataSetChanged();
                }
                btnCheckAll.setButtonDrawable(R.drawable.ic_select_all_empty);
                hideFragment(R.id.file_actions);
            } else {
                btnCheckAll.setButtonDrawable(R.drawable.ic_select_all_selected);
                showFragment(R.id.file_actions);
            }
            checkAll = !checkAll;
        }
    }

    private void sortName(){
        if (sort == 1) {
            oldPref.setPreferences("displaySort", 0);
        } else {
            oldPref.setPreferences("displaySort", 1);
        }
        sort = (int) oldPref.getPreferences("displaySort");
        generateAdapterView(tempItemList, sort);
    }

    private void sortDuration(){
        if (sort == 3) {
            oldPref.setPreferences("displaySort", 2);
        } else {
            oldPref.setPreferences("displaySort", 3);
        }
        sort = (int) oldPref.getPreferences("displaySort");
        generateAdapterView(tempItemList, sort);
    }

    private void sortDate(){
        if (sort == 5) {
            oldPref.setPreferences("displaySort", 4);
        } else {
            oldPref.setPreferences("displaySort", 5);
        }
        sort = (int) oldPref.getPreferences("displaySort");
        generateAdapterView(tempItemList, sort);
    }

    public void showShareDialog(View v){
        FragmentManager fm = getFragmentManager();
        FragmentShareDialog d = new FragmentShareDialog();
        d.setFilesForExporting(fileItemList, adapter, currentDir);
        d.setStyle(DialogFragment.STYLE_NO_TITLE, 0);
        d.show(fm, "Share Dialog");
    }

    public void showDeleteConfirmDialog(View v) {
        FragmentManager fm = getFragmentManager();
        FragmentDeleteDialog d = new FragmentDeleteDialog();
        d.setStyle(DialogFragment.STYLE_NO_TITLE, 0);
        d.show(fm, "Delete Confirm Dialog");
    }

    public void confirmDelete() {
        exportList = new ArrayList<String>();
        for (int i = 0; i < adapter.checkBoxState.length; i++) {
            if (adapter.checkBoxState[i]) {
                exportList.add(currentDir + "/" + fileItemList.get(i).getName());
            }
        }
        if (exportList.size() > 0) {
            deleteFiles(exportList);
            Toast.makeText(AudioFiles.this, "File has been deleted", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(AudioFiles.this, "Select a file to delete", Toast.LENGTH_SHORT).show();
        }
        sort = (int) oldPref.getPreferences("displaySort");
        generateAdapterView(tempItemList, sort);
        hideFragment(R.id.file_actions);
    }

    private void removeUnusedVisualizationFiles(String filesDir){
        File audioFilesLocation = new File(filesDir);
        File visFilesLocation = new File(AudioInfo.pathToVisFile);
        File[] visFiles = visFilesLocation.listFiles();
        File[] audioFiles = audioFilesLocation.listFiles();
        if(visFiles == null){
            return;
        }
        for(File v : visFiles){
            boolean found = false;
            if(audioFiles != null) {
                for (File a : audioFiles) {
                    //check if the names match up; exclude the path to get to them or the file extention
                    if (extractFilename(a).equals(extractFilename(v))) {
                        found = true;
                        break;
                    }
                }
            }
            if(!found){
                System.out.println("Removing " + v.getName());
                v.delete();
            }
        }
    }

    private String extractFilename(File a){
        if(a.isDirectory()){
            return "";
        }
        String nameWithExtention = a.getName();
        if(nameWithExtention.lastIndexOf('.') < 0 || nameWithExtention.lastIndexOf('.') > nameWithExtention.length()){
            return "";
        }
        String filename = nameWithExtention.substring(0, nameWithExtention.lastIndexOf('.'));
        return filename;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        mMenu = menu;
        return true;
    }

    /**
     *Clears Check Box State when the back button is pressed
     */
    public void onBackPressed(){
        if (file == null) {}
        else {
            exportList = new ArrayList<String>();
            if(adapter != null && adapter.checkBoxState != null)
            Arrays.fill(adapter.checkBoxState, Boolean.FALSE);
        }
        finish();
    }

    private void generateAdapterView(ArrayList<FileItem> tempItemList, int sort){
        //
        ArrayList<FileItem> cleanList = new ArrayList<FileItem>();
        for (int a = 0; a < tempItemList.size(); a++){
            cleanList.add(tempItemList.get(a));
        }

        // Clear list
        fileItemList = new ArrayList<FileItem>();
        fileItemList = sortAudioItem(cleanList, sort);

        // Temp array for Adapter
        FileItem[] tempArr = new FileItem[fileItemList.size()];
        for(int a = 0; a < fileItemList.size(); a++){
            tempArr[a] = fileItemList.get(a);

        }

        // Set Adapter view
        adapter = (new AudioFilesAdapter(this, tempArr));
        audioFileView.setAdapter(adapter);
    }

    private void deleteFiles(ArrayList<String> exportList){
        //int count = 0;

        for (int i = 0; i < exportList.size(); i++) {
            File file = new File(exportList.get(i));
//            boolean deleted = file.delete();
//            if (deleted){
            if (file.delete()) {
                String value = exportList.get(i).replace(currentDir + "/", "");
                for (int a = 0; a < tempItemList.size(); a++) {
                    if (tempItemList.get(a).getName().equals(value)){
                        tempItemList.remove(a);
                        a = tempItemList.size() + 2;
                    }
                }
                //tempItemList.remove(i - count);
                //System.out.println("========" + (i - count));
                //count++;
            }
        }
    }

    private ArrayList<FileItem> sortAudioItem(ArrayList<FileItem> nList, int sort) {
        //
        ArrayList<FileItem> outputList = new ArrayList<FileItem>();
        if (nList.size() > 0) {
            boolean flag = false;
            switch (sort) {
                case 0:
                case 2:
                case 4:
                    //false
                    break;
                case 1:
                case 3:
                case 5:
                default:
                    flag = true;
                    break;
            }

            int val = 0;
            int size = nList.size() - 1;

            if (sort == 0 || sort == 1) {
                String cmp = "";

                // As long as there are items...
                do {
                    //
                    size = nList.size() - 1;
                    cmp = nList.get(size).getName().toLowerCase();
                    val = size;

                    // Compare with other items
                    for (int x = 0; x < size; x++) {
                        if (cmp.compareTo(nList.get(x).getName().toLowerCase()) < 0) {
                            if (flag) {
                                //A-Z
                            } else {
                                //Z-A
                                val = x;
                                cmp = nList.get(x).getName();
                            }
                        } else {
                            if (flag) {
                                //A-Z
                                val = x;
                                cmp = nList.get(x).getName();
                            } else {
                                //Z-A
                            }
                        }
                    }

                    //
                    outputList.add(nList.get(val));
                    nList.remove(val);

                } while (size > 0);

            } else if (sort == 2 || sort == 3) {
                //
                Integer cmp = 0;

                // As long as there are items...
                do {
                    size = nList.size() - 1;
                    cmp = nList.get(size).getDuration();
                    val = size;

                    // Compare with other items
                    for (int x = 0; x < size; x++) {
                        if (cmp > nList.get(x).getDuration()) {
                            if (flag) {
                                //A-Z
                            } else {
                                //Z-A
                                val = x;
                                cmp = nList.get(x).getDuration();
                            }
                        } else {
                            if (flag) {
                                //A-Z
                                val = x;
                                cmp = nList.get(x).getDuration();
                            } else {
                                //Z-A
                            }
                        }
                    }

                    //
                    outputList.add(nList.get(val));
                    nList.remove(val);

                } while (size > 0);

            } else {
                //
                ArrayList<Date> tempList = new ArrayList<Date>();
                Date cmp = new Date();

                // As long as there are items
                do {
                    //
                    size = nList.size() - 1;
                    cmp = nList.get(size).getDate();
                    val = size;

                    // Compare with other items
                    for (int x = 0; x < size; x++) {
                        if (cmp.after(nList.get(x).getDate())) {
                            if (flag) {
                                //A-Z
                            } else {
                                //Z-A
                                val = x;
                                cmp = nList.get(x).getDate();
                            }
                        } else {
                            if (flag) {
                                // A-Z
                                val = x;
                                cmp = nList.get(x).getDate();
                            } else {
                                // Z-A
                            }
                        }
                    }

                    //
                    outputList.add(nList.get(val));
                    nList.remove(val);

                } while (size > 0);
            }

        } else {
            System.out.println("empty");
        }

        return outputList;
    }

    public void hideFragment(int view) {
        View fragment = findViewById(view);
        if (fragment.getVisibility() == View.VISIBLE) {
            fragment.setVisibility(View.GONE);
        }
    }

    public void showFragment(int view) {
        View fragment = findViewById(view);
        if (fragment.getVisibility() == View.GONE) {
            fragment.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void delegateExport(Export exp) {
        exp.setFragmentContext(mExportTaskFragment);
        mExportTaskFragment.delegateExport(exp);
    }
}

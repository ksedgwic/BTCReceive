// Copyright (C) 2014  Bonsai Software, Inc.
// 
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
// 
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
// 
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.bonsai.btcreceive;

import java.text.SimpleDateFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.annotation.SuppressLint;
import android.app.ActionBar.Tab;
import android.app.ActionBar.TabListener;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ProgressBar;
import android.widget.TextView;

public class MainActivity extends BaseWalletActivity {

    private static Logger mLogger =
        LoggerFactory.getLogger(MainActivity.class);

    private View mSyncDialogView = null;
    private DialogFragment mSyncProgressDialog = null;

    private View mStateDialogView = null;
    private DialogFragment mStateProgressDialog = null;

    private static SimpleDateFormat mDateFormatter =
        new SimpleDateFormat("yyyy-MM-dd kk:mm:ss");

	private MyAdapter mAdapter;
	private ViewPager mPager;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

        // Turn off "up" navigation since we are the top-level.
        getSupportActionBar().setDisplayHomeAsUpEnabled(false);

		setContentView(R.layout.activity_main);

		mAdapter = new MyAdapter(getSupportFragmentManager());
		mPager = (ViewPager) findViewById(R.id.pager);
		mPager.setAdapter(mAdapter);

        // Specify that tabs should be displayed in the action bar.
        final android.app.ActionBar actionBar = getActionBar();
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        // Create a tab listener that is called when the user changes tabs.
        TabListener tabListener = new TabListener() {
				@Override
				public void onTabReselected(Tab tab,
						android.app.FragmentTransaction ft) {
				}

				@Override
				public void onTabSelected(Tab tab,
						android.app.FragmentTransaction ft) {
                    // show the given tab
                    int position = tab.getPosition();
                    manageKeyboard(position);
                    mPager.setCurrentItem(position);
				}

				@Override
				public void onTabUnselected(Tab tab,
						android.app.FragmentTransaction ft) {
				}
            };

        // Add tabs to the view pager.
        actionBar.addTab(actionBar.newTab()
                         .setText(mRes.getString(R.string.tab_receive))
                         .setTabListener(tabListener));
        actionBar.addTab(actionBar.newTab()
                         .setText(mRes.getString(R.string.tab_transactions))
                         .setTabListener(tabListener));
        actionBar.addTab(actionBar.newTab()
                         .setText(mRes.getString(R.string.tab_account))
                         .setTabListener(tabListener));

        // Listen for swiped changes to the view pager.
        mPager.setOnPageChangeListener
            (new ViewPager.SimpleOnPageChangeListener() {
                    @Override
                    public void onPageSelected(int position) {
                        // When swiping between pages, select the
                        // corresponding tab.
                        manageKeyboard(position);
                        getActionBar().setSelectedNavigationItem(position);
                    }
                });


        mLogger.info("MainActivity created");
	}

    public void manageKeyboard(int position) {
        mLogger.info(String.format("manageKeyboard %d", position));
        if (position == 0) {
            ReceiveFragment rf =
                (ReceiveFragment) mAdapter.getActiveFragment(mPager, 0);
            if (rf != null)
                rf.maybeShowKeyboard();
        }
        else {
            hideKeyboard();
        }
    }

    public void hideKeyboard() {
        // Hide the keyboard.
        InputMethodManager imm = ((InputMethodManager) this.getSystemService
                                  (Context.INPUT_METHOD_SERVICE));
        imm.hideSoftInputFromWindow(mPager.getWindowToken(), 0);
    }

    public void setPagerItem(int position) {
        mPager.setCurrentItem(position);
    }

	public static class MyAdapter extends FragmentPagerAdapter {
        private FragmentManager mFragmentManager;

		public MyAdapter(FragmentManager fm) {
			super(fm);
            mFragmentManager = fm;
		}

		@Override
		public int getCount() {
			return 3;
		}

		@Override
		public Fragment getItem(int position) {
			switch (position) {
			case 0:
				return new ReceiveFragment();
			case 1:
				return new TransactionsFragment();
			case 2:
				return new AccountFragment();

			default:
				return null;
			}
		}

        public Fragment getActiveFragment(ViewPager container, int position) {
            String name = makeFragmentName(container.getId(), position);
            return  mFragmentManager.findFragmentByTag(name);
        }


        private static String makeFragmentName(int viewId, int index) {
            return "android:switcher:" + viewId + ":" + index;
        }
	}

	@Override
    protected void onWalletServiceBound() {
        onWalletStateChanged();
    }

	@Override
    protected void onWalletStateChanged() {
        if (mWalletService == null)
            return;

        switch (mWalletService.getState()) {
        case SETUP:
        case WALLET_SETUP:
        case KEYS_ADD:
        case PEERING:
            // All of these states use a progress dialog.
            if (mStateProgressDialog != null)
                updateStateMessage(mWalletService.getStateString());
            else
                showStateProgressDialog(mWalletService.getStateString());
            break;
        case SYNCING:
            if (mStateProgressDialog != null) {
                mStateProgressDialog.dismissAllowingStateLoss();
                mStateProgressDialog = null;
            }

            if (mSyncProgressDialog == null)
                showSyncProgressDialog();

            int pctdone = (int) mWalletService.getPercentDone();

            String timeLeft = formatTimeLeft(mWalletService.getMsecsLeft());

            updateSyncStats(String.format("%d%%", pctdone),
                            String.format("%d", mWalletService.getBlocksToGo()),
                            mDateFormatter.format(mWalletService.getScanDate()),
                            timeLeft);

            if (mSyncDialogView != null) {
                ProgressBar pb =
                    (ProgressBar) mSyncDialogView.findViewById(R.id.progress_bar);
                pb.setProgress(pctdone);
            }
            break;
        case READY:
            if (mStateProgressDialog != null) {
                mStateProgressDialog.dismissAllowingStateLoss();
                mStateProgressDialog = null;
                mStateDialogView = null;
            }

            if (mSyncProgressDialog != null) {
                mSyncProgressDialog.dismissAllowingStateLoss();
                mSyncProgressDialog = null;
                mSyncDialogView = null;
            }
            break;
        case SHUTDOWN:
            break;
        case ERROR:
            break;
        }
    }

	@Override
    protected void onRateChanged() {
    }

    private String formatTimeLeft(long msec) {
        final long SECOND = 1000;
        final long MINUTE = 60 * SECOND;
        final long HOUR = 60 * MINUTE;

        long hrs = msec / HOUR;
        long mins = (msec - (hrs * HOUR)) / MINUTE;
        long secs = (msec - (hrs * HOUR) - (mins * MINUTE)) / SECOND;

        if (msec > HOUR)
            return String.format("%d:%02d:%02d hrs", hrs, mins, secs);
        else if (msec > MINUTE)
            return String.format("%d:%02d min", mins, secs);
        else
            return String.format("%d sec", secs);
    }

    @SuppressLint("ValidFragment")
	public class StateProgressDialogFragment extends DialogFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setRetainInstance(true);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            String details = getArguments().getString("details");
            AlertDialog.Builder builder =
                new AlertDialog.Builder(getActivity());
            LayoutInflater inflater = getActivity().getLayoutInflater();
            mStateDialogView =
                inflater.inflate(R.layout.dialog_state_progress, null);
            TextView detailsTextView =
                (TextView) mStateDialogView.findViewById(R.id.state_details);
            detailsTextView.setText(details);
            builder.setView(mStateDialogView)
                .setNegativeButton(R.string.sync_abort,
                                   new DialogInterface.OnClickListener() {
                                       public void onClick(DialogInterface dialog,
                                                           int id) {
                                           mLogger.info("Abort sync selected");
                                           doExit();
                                       }
                                   });      
            return builder.create();
        }
    }

    private void showStateProgressDialog(String details) {
        DialogFragment df = new StateProgressDialogFragment();
        Bundle args = new Bundle();
        args.putString("details", details);
        df.setArguments(args);
        df.setCancelable(false);
        df.show(getFragmentManager(), "state_progress_dialog");
        mStateProgressDialog = df;
    }

    private void updateStateMessage(String msg) {
        if (mStateDialogView == null)
            return;
        TextView smtv =
            (TextView) mStateDialogView.findViewById(R.id.state_details);
        smtv.setText(msg);
    }

    @SuppressLint("ValidFragment")
	public class SyncProgressDialogFragment extends DialogFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setRetainInstance(true);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            String details = getArguments().getString("details");
            AlertDialog.Builder builder =
                new AlertDialog.Builder(getActivity());
            LayoutInflater inflater = getActivity().getLayoutInflater();
            mSyncDialogView = inflater.inflate(R.layout.dialog_sync_progress, null);
            TextView detailsTextView =
                (TextView) mSyncDialogView.findViewById(R.id.sync_details);
            detailsTextView.setText(details);
            builder.setView(mSyncDialogView)
                .setNegativeButton(R.string.sync_abort,
                                   new DialogInterface.OnClickListener() {
                                       public void onClick(DialogInterface dialog,
                                                           int id) {
                                           mLogger.info("Abort sync selected");
                                           doExit();
                                       }
                                   });      
            return builder.create();
        }
    }

    private void showSyncProgressDialog() {
        String details;

        switch(mWalletService.getSyncState()) {
        case CREATED:
            details = mRes.getString(R.string.sync_details_created);
            break;
        case RESTORE:
            details = mRes.getString(R.string.sync_details_restore);
            break;
        case STARTUP:
            details = mRes.getString(R.string.sync_details_startup);
            break;
        case RESCAN:
            details = mRes.getString(R.string.sync_details_rescan);
            break;
        case RERESCAN:
            details = mRes.getString(R.string.sync_details_rerescan);
            break;
        default:
            details = "???";	// Shouldn't happen
            break;
        }

        DialogFragment df = new SyncProgressDialogFragment();
        Bundle args = new Bundle();
        args.putString("details", details);
        df.setArguments(args);
        df.setCancelable(false);
        df.show(getFragmentManager(), "sync_progress_dialog");
        mSyncProgressDialog = df;
    }

    private void updateSyncStats(String pctstr, String blksstr,
                                 String datestr, String cmplstr) {
        if (mSyncDialogView == null)
            return;

        TextView pcttv = (TextView) mSyncDialogView.findViewById(R.id.percent);
        pcttv.setText(pctstr);

        TextView blkstv = (TextView) mSyncDialogView.findViewById(R.id.blocks_left);
        blkstv.setText(blksstr);
                
        TextView datetv = (TextView) mSyncDialogView.findViewById(R.id.scan_date);
        datetv.setText(datestr);

        TextView cmpltv = (TextView) mSyncDialogView.findViewById(R.id.scan_cmpl);
        cmpltv.setText(cmplstr);
    }


    public void viewAccount(View view) {
        int accountId = view.getId();
        Intent intent = new Intent(this, ViewAccountActivity.class);
        Bundle bundle = new Bundle();
        bundle.putInt("accountId", accountId);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    public void receiveBitcoin(View view) {
        Intent intent = new Intent(this, ReceiveBitcoinActivity.class);
        startActivity(intent);
    }

    public void viewTransactions(View view) {
        Intent intent = new Intent(this, ViewTransactionsActivity.class);
        startActivity(intent);
    }

    public void sweepKey(View view) {
        Intent intent = new Intent(this, SweepKeyActivity.class);
        startActivity(intent);
    }

    public void exitApp(View view) {
        mLogger.info("Exit selected");
        doExit();
    }
}

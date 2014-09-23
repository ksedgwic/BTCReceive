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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionConfidence;
import com.google.bitcoin.core.TransactionConfidence.ConfidenceType;
import com.google.bitcoin.wallet.WalletTransaction;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

public class TransactionsFragment extends Fragment {

    private static Logger mLogger =
        LoggerFactory.getLogger(TransactionsFragment.class);

    private boolean mUpdating = false;

    protected LocalBroadcastManager mLBM;

	@Override
	public void onCreate(Bundle savedInstanceState) {
        mLogger.info("TransactionsFragment onCreate");
		super.onCreate(savedInstanceState);
        mLBM = LocalBroadcastManager.getInstance(getActivity());
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
        mLogger.info("TransactionsFragment onActivityCreated");
		super.onActivityCreated(savedInstanceState);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        mLogger.info("TransactionsFragment onCreateView");
		View view =
            inflater.inflate(R.layout.transactions_fragment, container, false);
		return view;
	}

	@Override
	public void onResume() {
        mLogger.info("TransactionsFragment onResume");
        super.onResume();

        mLBM.registerReceiver(mWalletStateChangedReceiver,
                              new IntentFilter("wallet-state-changed"));
        mLBM.registerReceiver(mRateChangedReceiver,
                              new IntentFilter("rate-changed"));

        mLogger.info("TransactionsFragment resumed");
    }

    @Override
	public void onPause() {
        mLogger.info("TransactionsFragment onPause");
        super.onPause();

        mLBM.unregisterReceiver(mWalletStateChangedReceiver);
        mLBM.unregisterReceiver(mRateChangedReceiver);

        mLogger.info("TransactionsFragment paused");
    }

    private BroadcastReceiver mWalletStateChangedReceiver =
        new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateTransactions();
            }
        };

    private BroadcastReceiver mRateChangedReceiver =
        new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateTransactions();
            }
        };

    private void addTransactionHeader(TableLayout table) {
        TableRow row =
            (TableRow) LayoutInflater.from(getActivity())
            .inflate(R.layout.transaction_table_header, table, false);

        TextView tv = (TextView) row.findViewById(R.id.header_btc);
        tv.setText(BaseWalletActivity.getBTCFmt().unitStr());

        table.addView(row);
    }

    private void addTransactionRow(String hash,
                                   TableLayout table,
                                   String datestr,
                                   String timestr,
                                   String confstr,
                                   String btcstr,
                                   String btcbalstr,
                                   String fiatstr,
                                   String fiatbalstr) {
        TableRow row =
            (TableRow) LayoutInflater.from(getActivity())
            .inflate(R.layout.transaction_table_row, table, false);

        row.setTag(hash);

        row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    // Dispatch to the transaction viewer.
                    String hash = (String) view.getTag();
                    Intent intent = new Intent(getActivity(),
                                               ViewTransactionActivity.class);
                    intent.putExtra("hash", hash);
                    startActivity(intent);
                }
            }); 

        {
            TextView tv = (TextView) row.findViewById(R.id.row_date);
            tv.setText(datestr);
        }
        {
            TextView tv = (TextView) row.findViewById(R.id.row_time);
            tv.setText(timestr);
        }

        {
            TextView tv = (TextView) row.findViewById(R.id.row_confidence);
            tv.setText(confstr);
        }

        {
            TextView tv = (TextView) row.findViewById(R.id.row_btc_balance);
            tv.setText(btcbalstr);
        }
        {
            TextView tv = (TextView) row.findViewById(R.id.row_btc);
            tv.setText(btcstr);
        }

        {
            TextView tv = (TextView) row.findViewById(R.id.row_fiat_balance);
            tv.setText(fiatbalstr);
        }
        {
            TextView tv = (TextView) row.findViewById(R.id.row_fiat);
            tv.setText(fiatstr);
        }

        if (btcstr.charAt(0) == '-')
            row.setBackgroundColor(Color.argb(64, 255, 0, 0));
        else
            row.setBackgroundColor(Color.argb(64, 0, 255, 0));

        table.addView(row);
    }

    private class RowData {
        public String hash;
        public String datestr;
        public String timestr;
        public String confstr;
        public String btcstr;
        public String btcbalstr;
        public String fiatstr;
        public String fiatbalstr;

        public RowData(String hash,
                       String datestr,
                       String timestr,
                       String confstr,
                       String btcstr,
                       String btcbalstr,
                       String fiatstr,
                       String fiatbalstr) {
            this.hash = hash;
            this.datestr = datestr;
            this.timestr = timestr;
            this.confstr = confstr;
            this.btcstr = btcstr;
            this.btcbalstr = btcbalstr;
            this.fiatstr = fiatstr;
            this.fiatbalstr = fiatbalstr;
        }
    };

    private class UpdateTransactionsTask extends AsyncTask<Void, Void, Void> {
        private WalletService walletService;
        private ArrayList<RowData> rowdata;
        
        @Override
        protected void onPreExecute() {
            walletService =
                ((BaseWalletActivity) getActivity()).getWalletService();
        }

		protected Void doInBackground(Void... params)
        {
            if (walletService == null)
                return null;

            rowdata = new ArrayList<RowData>();

            mLogger.info("UpdateTransactionsTask doInBackground starting");

            SimpleDateFormat dateFormater =
                new SimpleDateFormat("yyyy-MM-dd");
            SimpleDateFormat timeFormater =
                new SimpleDateFormat("kk:mm:ss");

            Iterable<WalletTransaction> txit = walletService.getTransactions();
            // If we've been called before things are setup just bail.
            if (txit == null)
                return null;

            ArrayList<WalletTransaction> txs =
                new ArrayList<WalletTransaction>();
            for (WalletTransaction wtx : txit)
                txs.add(wtx);
            // Sort in reverse time order (most recent first).
            Collections.sort(txs, new Comparator<WalletTransaction>() {
                    public int compare(WalletTransaction wt0,
                                       WalletTransaction wt1) {
                        Date dt0 = wt0.getTransaction().getUpdateTime();
                        Date dt1 = wt1.getTransaction().getUpdateTime();
                        int cmp = -dt0.compareTo(dt1);
                        if (cmp == 0) {
                            // These two transactions happened in the
                            // same block (same time) so we should
                            // compare something else to keep the
                            // sorting order stable.
                            Sha256Hash h0 = wt0.getTransaction().getHash();
                            Sha256Hash h1 = wt1.getTransaction().getHash();
                            return -h0.compareTo(h1);
                        }
                        return cmp;
                    }
                });

            long btcbal = walletService.balanceForAccount();
        
            for (WalletTransaction wtx : txs) {
                Transaction tx = wtx.getTransaction();
                TransactionConfidence conf = tx.getConfidence();
                ConfidenceType ct = conf.getConfidenceType();

                long btc = walletService.amountForAccount(wtx);
                if (btc != 0) {
                    double fiat = BaseWalletActivity.getBTCFmt().fiatAtRate
                        (btc, ((BaseWalletActivity) getActivity())
                         .fiatPerBTC());
                    double fiatbal = BaseWalletActivity.getBTCFmt().fiatAtRate
                        (btcbal, ((BaseWalletActivity) getActivity())
                         .fiatPerBTC());

                    String hash = tx.getHashAsString();

                    String datestr = dateFormater.format(tx.getUpdateTime());
                    String timestr = timeFormater.format(tx.getUpdateTime());

                    String btcstr = BaseWalletActivity.getBTCFmt()
                        .formatCol(btc, 0, true);
                    if (btc > 0)
                        btcstr = '+' + btcstr;
                    String btcbalstr = '=' + BaseWalletActivity.getBTCFmt()
                        .formatCol(btcbal, 0, true);

                    String fiatstr = String.format("%.02f", fiat);
                    if (fiat > 0)
                        fiatstr = '+' + fiatstr;
                    String fiatbalstr = String.format("=%.02f", fiatbal);

                    String confstr;
                    switch (ct) {
                    case UNKNOWN: confstr = "U"; break;
                    case BUILDING:
                        int depth = conf.getDepthInBlocks();
                        confstr = depth > 100 ? "100+" :
                            String.format("%d", depth);
                        break;
                    case PENDING: confstr = "P"; break;
                    case DEAD: confstr = "D"; break;
                    default: confstr = "?"; break;
                    }

                    rowdata.add(new RowData(hash, datestr, timestr, confstr,
                                            btcstr, btcbalstr,
                                            fiatstr, fiatbalstr));
                }

                // We're working backward in time ...
                // Dead transactions should not affect the balance ...
                if (ct != ConfidenceType.DEAD)
                    btcbal -= btc;
            }

            mLogger.info("UpdateTransactionsTask doInBackground finished");
			return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            try {
                if (walletService == null)
                    return;

                mLogger.info("UpdateTransactionsTask onPostExecute starting");

                TableLayout table = (TableLayout) getActivity()
                    .findViewById(R.id.transaction_table);

                // Clear any existing table content.
                table.removeAllViews();

                addTransactionHeader(table);

                for (RowData rd : rowdata) {
                    addTransactionRow(rd.hash, table, rd.datestr,
                                      rd.timestr, rd.confstr,
                                      rd.btcstr, rd.btcbalstr,
                                      rd.fiatstr, rd.fiatbalstr);
                }
            }
            finally {
                mUpdating = false;
                mLogger.info("UpdateTransactionsTask onPostExecute finished");
            }
        }
    }

	private synchronized void updateTransactions() {
        if (!mUpdating) {
            mUpdating = true;
            new UpdateTransactionsTask().execute();
        }
    }
}

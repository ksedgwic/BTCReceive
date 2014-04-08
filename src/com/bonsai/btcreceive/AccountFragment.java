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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

public class AccountFragment extends Fragment {

    private static Logger mLogger =
        LoggerFactory.getLogger(AccountFragment.class);

    private HDAccount mAccount = null;

    protected LocalBroadcastManager mLBM;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
        mLBM = LocalBroadcastManager.getInstance(getActivity());
        mLogger.info("AccountFragment onCreate");
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
        mLogger.info("AccountFragment onActivityCreated");
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
		View view =
            inflater.inflate(R.layout.account_fragment, container, false);
        mLogger.info("AccountFragment onCreateView");
		return view;
	}

	@Override
	public void onResume() {
        super.onResume();

        mLBM.registerReceiver(mWalletStateChangedReceiver,
                              new IntentFilter("wallet-state-changed"));
        mLBM.registerReceiver(mRateChangedReceiver,
                              new IntentFilter("rate-changed"));

        updateChains();

        mLogger.info("AccountFragment resumed");
    }

    @Override
	public void onPause() {
        super.onPause();

        mLBM.unregisterReceiver(mWalletStateChangedReceiver);
        mLBM.unregisterReceiver(mRateChangedReceiver);

        mLogger.info("AccountFragment paused");
    }

    private BroadcastReceiver mWalletStateChangedReceiver =
        new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateChains();
            }
        };

    private BroadcastReceiver mRateChangedReceiver =
        new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateChains();
            }
        };

    private void updateChains() {
        WalletService walletService =
            ((BaseWalletActivity) getActivity()).getWalletService();
        if (walletService == null)
            return;

        mAccount = walletService.getAccount();
        if (mAccount == null)
            return;

        updateChain(R.id.receive_table, mAccount.getReceiveChain());
        updateChain(R.id.change_table, mAccount.getChangeChain());
    }

    private void addAddressHeader(TableLayout table) {
        TableRow row =
            (TableRow) LayoutInflater.from(getActivity())
            .inflate(R.layout.address_table_header, table, false);

        TextView tv = (TextView) row.findViewById(R.id.header_btc);
        tv.setText(BaseWalletActivity.getBTCFmt().unitStr());

        table.addView(row);
    }

    private void addAddressRow(int tableId,
                               int index,
                               TableLayout table,
                               String path,
                               String addr,
                               String ntrans,
                               String btcstr,
                               String fiatstr) {
        TableRow row =
            (TableRow) LayoutInflater.from(getActivity())
            .inflate(R.layout.address_table_row, table, false);

        row.setTag(tableId);
        row.setId(index);

        row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    int tableId = (Integer) view.getTag();
                    int index = view.getId();
                    viewAddress(tableId, index);
                }
            }); 

        {
            TextView tv = (TextView) row.findViewById(R.id.row_path);
            tv.setText(path);
        }

        {
            TextView tv = (TextView) row.findViewById(R.id.row_addr);
            tv.setText(addr);
        }

        {
            TextView tv = (TextView) row.findViewById(R.id.row_ntrans);
            tv.setText(ntrans);
        }

        {
            TextView tv = (TextView) row.findViewById(R.id.row_btc);
            tv.setText(btcstr);
        }

        {
            TextView tv = (TextView) row.findViewById(R.id.row_fiat);
            tv.setText(fiatstr);
        }

        table.addView(row);
    }

    public void viewAddress(int tableId, int index) {
        HDChain chain = null;
        switch (tableId) {
        case R.id.receive_table:
            mLogger.info(String.format("receive row %d clicked", index));
            chain = mAccount.getReceiveChain();
            break;
        case R.id.change_table:
            mLogger.info(String.format("change row %d clicked", index));
            chain = mAccount.getChangeChain();
            break;
        }

        List<HDAddress> addrs = chain.getAddresses();
        HDAddress addr = addrs.get(index);
        String addrstr = addr.getAddressString();
        
        // Dispatch to the address viewer.
        Intent intent = new Intent(getActivity(), ViewAddressActivity.class);
        intent.putExtra("address", addrstr);
        startActivity(intent);
    }

    private void updateChain(int tableId, HDChain chain) {
        TableLayout table = (TableLayout) getActivity().findViewById(tableId);

        // Clear any existing table content.
        table.removeAllViews();

        mLogger.info(String.format("updateChain tableId=%d", tableId));

        addAddressHeader(table);

        // Read all of the addresses.  Presume order is correct ...
        int ndx = 0;
        List<HDAddress> addrs = chain.getAddresses();
        for (HDAddress addr : addrs) {
            String path = addr.getPath();
            String addrstr = addr.getAbbrev();
            String ntrans = String.format("%d", addr.numTrans());
            String bal = BaseWalletActivity.getBTCFmt()
                .formatCol(addr.getBalance(), 0, true);
            String fiat = String.format
                ("%.02f", BaseWalletActivity.getBTCFmt()
                 .fiatAtRate(addr.getBalance(),
                             ((BaseWalletActivity) getActivity()).fiatPerBTC()));
            addAddressRow(tableId, ndx++, table, path,
                          addrstr, ntrans, bal, fiat);
        }
    }
}

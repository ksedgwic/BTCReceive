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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.params.MainNetParams;

import eu.livotov.zxscan.ZXScanHelper;

public class ScanXPubActivity extends ActionBarActivity {

    private static Logger mLogger =
        LoggerFactory.getLogger(ScanXPubActivity.class);

    private Resources			mRes;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
        mRes = getApplicationContext().getResources();
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_scan_xpub);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.lobby_actions, menu);
		return true;
	}

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle presses on the action bar items
        Intent intent;
        switch (item.getItemId()) {
        case R.id.action_about:
            intent = new Intent(this, AboutActivity.class);
            startActivity(intent);
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

	protected void onActivityResult(final int requestCode,
                                    final int resultCode,
                                    final Intent data)
    {
        if (resultCode != RESULT_OK || requestCode != 12347)
        {
            String msg = mRes.getString(R.string.scan_xpub_scanfail);
            mLogger.warn(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return;
        }

        String scannedCode = ZXScanHelper.getScannedCode(data);

        DeterministicKey accountKey;
        try {
            accountKey = WalletUtil.createMasterPubKeyFromPubB58(scannedCode);
        }
        catch (Exception ex) {
            String msg = "trouble deserializing xpub: " + ex.toString();
            mLogger.error(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            mLogger.error("scanned: " + scannedCode);
            return;
        }

        // Setup the wallet in a background task.
        new ScanXPubTask().execute(accountKey);
    }

    private class ScanXPubTask extends AsyncTask<DeterministicKey, Void, Void> {
        private ProgressDialog progressDialog;

        @Override
        protected void onPreExecute() {
            progressDialog = ProgressDialog.show
                (ScanXPubActivity.this, "",
                 mRes.getString(R.string.scan_xpub_wait_setup));
        }

		protected Void doInBackground(DeterministicKey... args)
        {
            DeterministicKey accountKey = args[0];

            WalletApplication wallapp =
                (WalletApplication) getApplicationContext();
            NetworkParameters params = MainNetParams.get();
            String filePrefix = "btcreceive";

            HDReceiver hdrecvr = new HDReceiver(getApplicationContext(),
                                     params,
                                     getApplicationContext().getFilesDir(),
                                     filePrefix,
                                     accountKey);
            hdrecvr.persist();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            progressDialog.dismiss();

            // Spin up the WalletService.
            Intent svcintent =
                new Intent(ScanXPubActivity.this, WalletService.class);
            Bundle bundle = new Bundle();
            bundle.putString("SyncState", "RESTORE");
            svcintent.putExtras(bundle);
            startService(svcintent);

            Intent intent =
                new Intent(ScanXPubActivity.this, MainActivity.class);
            startActivity(intent);

            // Prevent the user from coming back here.
            finish();
        }
    }

    public void scanXPubCode(View view) {
        mLogger.info("scanning pairing code");

        // CaptureActivity
        ZXScanHelper.setCustomScanSound(R.raw.quiet_beep);
        ZXScanHelper.setCustomScanLayout(R.layout.scanner_layout);
        ZXScanHelper.scan(this, 12347);
    }
}

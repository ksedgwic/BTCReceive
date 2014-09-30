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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.InvalidCipherTextException;
import org.spongycastle.crypto.params.KeyParameter;
import org.spongycastle.util.encoders.Hex;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.LocalBroadcastManager;

import com.google.bitcoin.core.AbstractWalletEventListener;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionBroadcaster;
import com.google.bitcoin.core.TransactionConfidence;
import com.google.bitcoin.core.TransactionConfidence.ConfidenceType;
import com.google.bitcoin.core.TransactionInput;
import com.google.bitcoin.core.TransactionOutPoint;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.Wallet.BalanceType;
import com.google.bitcoin.crypto.KeyCrypter;
import com.google.bitcoin.params.MainNetParams;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.wallet.WalletTransaction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

public class WalletService extends Service
    implements OnSharedPreferenceChangeListener {

    public static boolean mIsRunning = false;

    private static Logger mLogger =
        LoggerFactory.getLogger(WalletService.class);

    public enum State {
        SETUP,			// CTOR
        WALLET_SETUP,	// Setting up wallet app kit.
        KEYS_ADD,		// Adding keys.
        PEERING,		// Connecting to peers.
        SYNCING,		// Many times from sync progress.
        READY,
        SHUTDOWN,
        ERROR
    }

    public enum SyncState {
        CREATED,		// First scan after creation.
        RESTORE,		// Scanning to restore.
        STARTUP,		// Catching up on startup.
        RESCAN,			// Rescanning blockchain.
        RERESCAN,		// Needed to rescan due to margin.
        SYNCHRONIZED	// We were synchronized.
    }

    private int NOTIFICATION = R.string.wallet_service_started;

    private NotificationManager		mNM;
    private LocalBroadcastManager	mLBM;

    private final IBinder mBinder = new WalletServiceBinder();

    private State				mState;
    private SyncState			mSyncState;
    private MyWalletAppKit		mKit;
    private NetworkParameters	mParams;
    private SetupWalletTask		mTask;
    private Context				mContext;
    private Resources			mRes;
    private double				mPercentDone = 0.0;
    private int					mBlocksToGo;
    private Date				mScanDate;
    private long				mMsecsLeft;

    private KeyCrypter			mKeyCrypter;
    private KeyParameter		mAesKey;
    private HDReceiver			mHDReceiver = null;

    private RateUpdater			mRateUpdater;

    private BigInteger			mBalanceAvailable;
    private BigInteger			mBalanceEstimated;

	private WakeLock			mWakeLock;

    private volatile int		mNoteId = 0;

    private static final String mFilePrefix = "btcreceive";

    private MyDownloadListener mkDownloadListener() {
        return new MyDownloadListener() {
            protected void progress(double pct, int blocksToGo, Date date, long msecsLeft) {
                Date cmplDate = new Date(System.currentTimeMillis() + msecsLeft);
                mLogger.info(String.format("CHAIN DOWNLOAD %d%% DONE WITH %d BLOCKS TO GO, COMPLETE AT %s",
                                           (int) pct, blocksToGo,
                                           DateFormat.getDateTimeInstance().format(cmplDate)));
                mBlocksToGo = blocksToGo;
                mScanDate = date;
                mMsecsLeft = msecsLeft;
                if (mPercentDone != pct) {
                    mPercentDone = pct;
                    setState(State.SYNCING);
                }
            }
        };
    }

    private AbstractWalletEventListener mWalletListener =
        new AbstractWalletEventListener() {
            @Override
			public void onCoinsReceived(Wallet wallet,
                                        Transaction tx,
                                        BigInteger prevBalance,
                                        BigInteger newBalance)
            {
                BigInteger amt = newBalance.subtract(prevBalance);
                final long amount = amt.longValue();

                WalletApplication app =
                    (WalletApplication) getApplicationContext();
                final BTCFmt btcfmt = app.getBTCFmt();

                // Change coins will be part of a balance transaction
                // that is negative in value ... skip them ...
                if (amount < 0)
                    return;

                // We allocate a new notification id for each receive.
                // We use it on both the receive and confirm so it
                // will replace the receive note with the confirm ...
                final int noteId = ++mNoteId;

                mLogger.info(String.format("showing notification receive %d",
                                           amount));

                showEventNotification
                    (noteId,
                     R.drawable.ic_note_bc_green_lt,
                     mRes.getString(R.string.wallet_service_note_rcvd_title,
                                    btcfmt.unitStr()),
                     mRes.getString(R.string.wallet_service_note_rcvd_msg,
                                    btcfmt.format(amount), btcfmt.unitStr()));

                final TransactionConfidence txconf = tx.getConfidence();

                final TransactionConfidence.Listener listener = 
                    new TransactionConfidence.Listener() {
                        @Override
                        public void onConfidenceChanged(Transaction tx,
                                                        ChangeReason reason) {
                            // Wait until it's not pending anymore.
                            if (tx.isPending())
                                return;
                 
                            ConfidenceType ct =
                                tx.getConfidence().getConfidenceType();
                        
                            if (ct == ConfidenceType.BUILDING) {
                                mLogger.info(String.format("receive %d confirm",
                                                           amount));

                                // Notify confirmed.
                                showEventNotification
                                    (noteId,
                                     R.drawable.ic_note_bc_green,
                                     mRes.getString
                                     (R.string.wallet_service_note_rcnf_title,
                                      btcfmt.unitStr()),
                                     mRes.getString
                                     (R.string.wallet_service_note_rcnf_msg,
                                      btcfmt.format(amount), btcfmt.unitStr()));

                            }
                            else if (ct == ConfidenceType.DEAD) {
                                mLogger.info(String.format("receive %d dead",
                                                           amount));
                                // Notify dead.
                                showEventNotification
                                    (noteId,
                                     R.drawable.ic_note_bc_gray,
                                     mRes.getString
                                     (R.string.wallet_service_note_rdead_title,
                                      btcfmt.unitStr()),
                                     mRes.getString
                                     (R.string.wallet_service_note_rdead_msg,
                                      btcfmt.format(amount), btcfmt.unitStr()));

                            }
                            else {
                                mLogger.info(String.format("receive %d unknown",
                                                           amount));
                            }

                            // We're all done listening ...
                            txconf.removeEventListener(this);
                        }
                    };

                txconf.addEventListener(listener);
            }

            @Override
			public void onCoinsSent(Wallet wallet,
                                    Transaction tx,
                                    BigInteger prevBalance,
                                    BigInteger newBalance)
            {
                BigInteger amt = prevBalance.subtract(newBalance);
                final long amount = amt.longValue();

                WalletApplication app =
                    (WalletApplication) getApplicationContext();
                final BTCFmt btcfmt = app.getBTCFmt();

                // We allocate a new notification id for each receive.
                // We use it on both the receive and confirm so it
                // will replace the receive note with the confirm ...
                final int noteId = ++mNoteId;

                mLogger.info(String.format("showing notification send %d",
                                           amount));

                showEventNotification
                    (noteId,
                     R.drawable.ic_note_bc_red_lt,
                     mRes.getString(R.string.wallet_service_note_sent_title,
                                    btcfmt.unitStr()),
                     mRes.getString(R.string.wallet_service_note_sent_msg,
                                    btcfmt.format(amount), btcfmt.unitStr()));

                final TransactionConfidence txconf = tx.getConfidence();

                final TransactionConfidence.Listener listener = 
                    new TransactionConfidence.Listener() {
                        @Override
                        public void onConfidenceChanged(Transaction tx,
                                                        ChangeReason reason) {
                            // Wait until it's not pending anymore.
                            if (tx.isPending())
                                return;
                 
                            ConfidenceType ct =
                                tx.getConfidence().getConfidenceType();
                        
                            if (ct == ConfidenceType.BUILDING) {
                                mLogger.info(String.format("send %d confirm",
                                                           amount));

                                // Show no longer pending.
                                showEventNotification
                                    (noteId,
                                     R.drawable.ic_note_bc_red,
                                     mRes.getString
                                     (R.string.wallet_service_note_scnf_title,
                                      btcfmt.unitStr()),
                                     mRes.getString
                                     (R.string.wallet_service_note_scnf_msg,
                                      btcfmt.format(amount), btcfmt.unitStr()));
                            }
                            else if (ct == ConfidenceType.DEAD) {
                                mLogger.info(String.format("send %d dead",
                                                           amount));
                                // Notify dead.
                                showEventNotification
                                    (noteId,
                                     R.drawable.ic_note_bc_gray,
                                     mRes.getString
                                     (R.string.wallet_service_note_sdead_title,
                                      btcfmt.unitStr()),
                                     mRes.getString
                                     (R.string.wallet_service_note_sdead_msg,
                                      btcfmt.format(amount), btcfmt.unitStr()));

                            }
                            else {
                                mLogger.info(String.format("send %d unknown",
                                                           amount));
                            }

                            // We're all done listening ...
                            txconf.removeEventListener(this);
                        }
                    };

                txconf.addEventListener(listener);
            }

            @Override
            public void onWalletChanged(Wallet wallet) {
                // Compute balances and transaction counts.
                Iterable<WalletTransaction> iwt =
                    mKit.wallet().getWalletTransactions();
                mHDReceiver.applyAllTransactions(iwt);

                // Check to make sure we have sufficient margins.
                int maxExtended = mHDReceiver.ensureMargins(mKit.wallet());

                // Persist the new state.
                mHDReceiver.persist();

                Intent intent = new Intent("wallet-state-changed");
                mLBM.sendBroadcast(intent);

                if (maxExtended > HDChain.maxSafeExtend()) {
                    mLogger.info(String.format("%d addresses added, rescanning",
                                               maxExtended));
                    rescanBlockchain(HDAddress.EPOCH);
                }
            }
        };

    public void shutdown() {
        mLogger.info("shutdown");
        mState = State.SHUTDOWN;
        try {
            if (mKit != null)
                mKit.shutDown();
        }
        catch (Exception ex) {
            mLogger.error("Trouble during shutdown: " + ex.toString());
        }
    }
    
    private class SetupWalletTask extends AsyncTask<Long, Void, Integer> {

        @Override
        protected void onPreExecute() {
            mWakeLock.acquire();
            mLogger.info("wakelock acquired");
        }

		@Override
		protected Integer doInBackground(Long... params)
        {
            // scanTime  0 : full rescan
            // scanTime  t : scan from time t
            final Long scanTime = params[0];
            WalletApplication wallapp = (WalletApplication) mContext;

            setState(State.WALLET_SETUP);

            mLogger.info("setting up wallet, scanTime=" +
                         scanTime.toString());

            mLogger.info("getting network parameters");

            mParams = MainNetParams.get();

            // Try to restore existing wallet.
            mHDReceiver = null;
            try {
				mHDReceiver =
                    HDReceiver.restore(mContext,
                                       mParams,
                                       mContext.getFilesDir(),
                                       mFilePrefix, mKeyCrypter, mAesKey);
			} catch (InvalidCipherTextException ex) {
                mLogger.error("wallet restore failed: " + ex.toString());
			} catch (IOException ex) {
                mLogger.error("wallet restore failed: " + ex.toString());
			} catch (RuntimeException ex) {
                mLogger.error("wallet restore failed: " + ex.toString());
			}

            if (mHDReceiver == null) {

                mLogger.error("WalletService started with bad HDReceiver");
                System.exit(0);
            }

            mLogger.info("creating new wallet app kit");

            // Checkpointing fails on full rescan because the earliest
            // create time is earlier than the genesis block time.
            //
            InputStream chkpntis = null;
            if (scanTime != 0) {
                try {
                    chkpntis = getAssets().open("checkpoints");
                } catch (IOException e) {
                    chkpntis = null;
                }
            }

            mKit = new MyWalletAppKit(mParams,
                                      mContext.getFilesDir(),
                                      mFilePrefix,
                                      mKeyCrypter,
                                      scanTime)
                {
                    @Override
                    protected void onSetupCompleted() {
                        mLogger.info("adding keys");

                        setState(WalletService.State.KEYS_ADD);

                        // Add all the existing keys, they'll be
                        // ignored if they are already in the
                        // WalletAppKit.
                        //
                        ArrayList<ECKey> keys = new ArrayList<ECKey>();
                        mHDReceiver.gatherAllKeys(scanTime, keys);
                        mLogger.info(String.format("adding %d keys",
                                                   keys.size()));
                        wallet().addKeys(keys);

                        // Do we have enough margin on all our chains?
                        // Add keys to chains which don't have enough
                        // unused addresses at the end.
                        //
                        mHDReceiver.ensureMargins(wallet());

                        // We don't need to check for HDChain.maxSafeExtend()
                        // here because we are about to scan anyway.
                        // We'll check again after the scan ...

                        // Now we're peering.
                        setState(WalletService.State.PEERING);
                    }
                };
            mKit.setDownloadListener(mkDownloadListener());
            if (chkpntis != null)
                mKit.setCheckpoints(chkpntis);

            setState(State.WALLET_SETUP);

            mLogger.info("waiting for blockchain setup");

            // Download the block chain and wait until it's done.
            mKit.startAndWait();

            mLogger.info("blockchain setup finished, state = " +
                         getStateString());

            // Bail if we're being shutdown ...
            if (mState == State.SHUTDOWN) {
                mHDReceiver.persist();
                return null;
            }

            mBalanceAvailable = mKit.wallet().getBalance(BalanceType.AVAILABLE);
            mBalanceEstimated = mKit.wallet().getBalance(BalanceType.ESTIMATED);

            mLogger.info("avail balance = " + mBalanceAvailable.toString());
            mLogger.info("estim balance = " + mBalanceEstimated.toString());

            // Compute balances and transaction counts.
            Iterable<WalletTransaction> iwt =
                mKit.wallet().getWalletTransactions();
            mHDReceiver.applyAllTransactions(iwt);

            // Check the margins again, since transactions may have arrived.
            int maxExtended = mHDReceiver.ensureMargins(mKit.wallet());

            // Persist the new state.
            mHDReceiver.persist();

            // Listen for future wallet changes.
            mKit.wallet().addEventListener(mWalletListener);

            setState(State.READY);	// This may be temporary ...

			return maxExtended;
		}

        @Override
        protected void onPostExecute(Integer maxExtended) {

            mWakeLock.release();
            mLogger.info("wakelock released");

            // Do we need another rescan?
            if (maxExtended > HDChain.maxSafeExtend()) {
                mLogger.info(String.format("rescan extended by %d, rescanning",
                                           maxExtended));
                rescanBlockchain(HDAddress.EPOCH);
            }
            else {
                mLogger.info("synchronized");
                setSyncState(SyncState.SYNCHRONIZED);
            }
        }
    }

    public WalletService() {
        mState = State.SETUP;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate()
    {
        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mLBM = LocalBroadcastManager.getInstance(this);

        mLogger.info("WalletService created");

        mContext = getApplicationContext();
        mRes = mContext.getResources();

		final String lockName = getPackageName() + " blockchain sync";
		final PowerManager pm =
            (PowerManager) getSystemService(Context.POWER_SERVICE);
		mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName);

        SharedPreferences sharedPref =
            PreferenceManager.getDefaultSharedPreferences(this);
        String fiatRateSource =
            sharedPref.getString(SettingsActivity.KEY_FIAT_RATE_SOURCE, "");
        setFiatRateSource(fiatRateSource);

        // Register for future preference changes.
        sharedPref.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        WalletApplication wallapp = (WalletApplication) getApplicationContext();

        // Establish our SyncState
        Bundle bundle = intent.getExtras();
        String syncStateStr = bundle.getString("SyncState");
        if (syncStateStr == null)
            syncStateStr = "STARTUP";
        mSyncState =
            syncStateStr.equals("CREATED")	? SyncState.CREATED :
            syncStateStr.equals("RESTORE")	? SyncState.RESTORE :
            syncStateStr.equals("STARTUP")	? SyncState.STARTUP :
            syncStateStr.equals("RESCAN")	? SyncState.RESCAN :
            syncStateStr.equals("RERESCAN")	? SyncState.RERESCAN :
            SyncState.STARTUP;
            
        mKeyCrypter = wallapp.mKeyCrypter;
        mAesKey = wallapp.mAesKey;

        // Set any new key's creation time to now.
        long now = Utils.now().getTime() / 1000;

        mTask = new SetupWalletTask();
        mTask.execute(now);

        mLogger.info("WalletService started");

        showStatusNotification();

        mIsRunning = true;

        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        mLogger.info("onDestroy called");
        
        mIsRunning = false;

        // FIXME - Where does this go?  Anywhere?
        // stopForeground(true);

        mNM.cancel(NOTIFICATION);
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {
        if (key.equals(SettingsActivity.KEY_FIAT_RATE_SOURCE)) {
            SharedPreferences sharedPref =
                PreferenceManager.getDefaultSharedPreferences(this);
            String fiatRateSource =
                sharedPref.getString(SettingsActivity.KEY_FIAT_RATE_SOURCE, "");
            setFiatRateSource(fiatRateSource);
        }
    }

    // Show a notification while this service is running.
    //
    private void showStatusNotification() {
        // In this sample, we'll use the same text for the ticker and
        // the expanded notification
        CharSequence started_txt = getText(R.string.wallet_service_started);
        CharSequence info_txt = getText(R.string.wallet_service_info);

        Notification note = new Notification(R.drawable.ic_stat_notify,
                                             started_txt,
                                             System.currentTimeMillis());

        Intent intent = new Intent(this, MainActivity.class);
    
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP);
    
        PendingIntent contentIntent =
            PendingIntent.getActivity(this, 0, intent, 0);
      
        // Set the info for the views that show in the notification panel.
        note.setLatestEventInfo(this, getText(R.string.wallet_service_label),
                                info_txt, contentIntent);

        note.flags |= Notification.FLAG_NO_CLEAR;

        startForeground(NOTIFICATION, note);
    }

    private void showEventNotification(int noteId,
                                       int icon,
                                       String title,
                                       String msg) {
        NotificationCompat.Builder mBuilder =
            new NotificationCompat.Builder(this)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(msg)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_LIGHTS |
                         Notification.DEFAULT_SOUND |
                         Notification.DEFAULT_VIBRATE);

        // Creates an explicit intent for an Activity in your app
        Intent intent = new Intent(this, MainActivity.class);

        // The stack builder object will contain an artificial back
        // stack for the started Activity.  This ensures that
        // navigating backward from the Activity leads out of your
        // application to the Home screen.
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        // Adds the back stack for the Intent (but not the Intent itself)
        stackBuilder.addParentStack(MainActivity.class);
        // Adds the Intent that starts the Activity to the top of the stack
        stackBuilder.addNextIntent(intent);
        PendingIntent resultPendingIntent =
            stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentIntent(resultPendingIntent);

        mNM.notify(noteId, mBuilder.build());
    }

    public void persist() {
        mHDReceiver.persist();
    }

    public void changePasscode(KeyParameter oldAesKey,
                               KeyCrypter keyCrypter,
                               KeyParameter aesKey) {
        mLogger.info("changePasscode starting");

        // Change the parameters on our HDReceiver.
        // mHDReceiver.setPersistCrypter(keyCrypter, aesKey);
        mHDReceiver.persist();

        mLogger.info("persisted HD wallet");

        // Decrypt the wallet with the old key.
        mKit.wallet().decrypt(oldAesKey);

        mLogger.info("decrypted base wallet");

        // Encrypt the wallet using the new key.
        mKit.wallet().encrypt(keyCrypter, aesKey);

        mLogger.info("reencrypted base wallet");
    }

    private void setFiatRateSource(String src) {

        if (mRateUpdater != null) {
            mRateUpdater.stopUpdater();
            mRateUpdater = null;
        }
        if (src.equals("BITSTAMPUSD")) {
            mLogger.info("Switching to BitStamp USD");
            mRateUpdater = new BitStampRateUpdater(getApplicationContext());
        } else
        if (src.startsWith("COINDESK")) {
            String code = src.substring(8);
            mLogger.info("Switching to CoinDesk BPI " + code);
            mRateUpdater = new CoinDeskRateUpdater(getApplicationContext(), code);
        }
        else {
            mLogger.warn("Unknown fiat rate source " + src);
            return;
        }

        mRateUpdater.startUpdater();
    }

    public void rescanBlockchain(long rescanTime) {
        mLogger.info(String.format("RESCANNING from %d", rescanTime));

        // Make sure we are in a good state for this.
        if (mState != State.READY) {
            mLogger.warn("can't rescan until the wallet is ready");
            return;
        }

        switch (mSyncState) {
        case SYNCHRONIZED:
            mSyncState = SyncState.RESCAN;
            break;
        default:
            mSyncState = SyncState.RERESCAN;
            break;
        }

        // Remove our wallet event listener.
        mKit.wallet().removeEventListener(mWalletListener);

        // Persist and remove our HDReceiver.
        //
        // NOTE - It's best not to clear the balances here.  When the
        // transactions are filling in on the transactions screen it's
        // disturbing to see negative historical balances.  They'll
        // get completely refigured when the sync is done anyway ...
        //
        mHDReceiver.persist();
        mHDReceiver = null;

        mLogger.info("resetting wallet state");
        mKit.wallet().clearTransactions(0); 
        mKit.wallet().setLastBlockSeenHeight(-1); // magic value 
        mKit.wallet().setLastBlockSeenHash(null); 

        mLogger.info("shutting kit down");
        try {
			mKit.shutDown();
            mKit = null;
		} catch (Exception ex) {
            mLogger.error("kit shutdown failed: " + ex.toString());
            return;
		}

        mLogger.info("removing spvchain file");
        File chainFile =
            new File(mContext.getFilesDir(), mFilePrefix + ".spvchain");
        if (!chainFile.delete())
            mLogger.error("delete of spvchain file failed");

        mLogger.info("restarting wallet");
        WalletApplication wallapp = (WalletApplication) getApplicationContext();

        mKeyCrypter = wallapp.mKeyCrypter;
        mAesKey = wallapp.mAesKey;

        setState(State.SETUP);
        mTask = new SetupWalletTask();
        mTask.execute(rescanTime);
    }

    public void setSyncState(SyncState syncState) {
        mSyncState = syncState;
    }

    public State getState() {
        return mState;
    }

    public SyncState getSyncState() {
        return mSyncState;
    }

    public double getPercentDone() {
        return mPercentDone;
    }

    public int getBlocksToGo() {
        return mBlocksToGo;
    }

    public Date getScanDate() {
        return mScanDate;
    }

    public long getMsecsLeft() {
        return mMsecsLeft;
    }

    public String getStateString() {
        switch (mState) {
        case SETUP:
            return mRes.getString(R.string.network_status_setup);
        case WALLET_SETUP:
            return mRes.getString(R.string.network_status_wallet);
        case KEYS_ADD:
            return mRes.getString(R.string.network_status_keysadd);
        case PEERING:
            return mRes.getString(R.string.network_status_peering);
        case SYNCING:
            return mRes.getString(R.string.network_status_sync,
                                  (int) mPercentDone);
        case READY:
            return mRes.getString(R.string.network_status_ready);
        case SHUTDOWN:
            return mRes.getString(R.string.network_status_shutdown);
        case ERROR:
            return mRes.getString(R.string.network_status_error);
        default:
            return mRes.getString(R.string.network_status_unknown);
        }
    }

    public class WalletServiceBinder extends Binder {
        WalletService getService() {
            return WalletService.this;
        }
    }

    public NetworkParameters getParams() {
        return mParams;
    }

    public double getRate() {
        return mRateUpdater == null ? 0.0 : mRateUpdater.getRate();
    }

    public String getCode() {
        return mRateUpdater == null ? "???" : mRateUpdater.getCode();
    }

    static public long getDefaultFee() {
        final BigInteger dmtf = Transaction.REFERENCE_DEFAULT_MIN_TX_FEE;
        return dmtf.longValue();
    }

    public HDAccount getAccount() {
        if (mHDReceiver == null)
            return null;
        return mHDReceiver.getAccount();
    }

    public Balance getBalance() {
        if (mHDReceiver == null)
            return null;
        return mHDReceiver.getBalance();
    }

    public Iterable<WalletTransaction> getTransactions() {
        if (mHDReceiver == null)
            return null;

        if (mState != State.READY)
            return null;

        return mKit.wallet().getWalletTransactions();
    }

    public Transaction getTransaction(String hashstr) {
        Sha256Hash hash = new Sha256Hash(hashstr);
        return mKit.wallet().getTransaction(hash);
    }

    public Address nextReceiveAddress(){
        return mHDReceiver.nextReceiveAddress();
    }

    public HDAddressDescription findAddress(Address addr) {
        return mHDReceiver.findAddress(addr);
    }

    public static class AmountAndFee {
        public long		mAmount;
        public long		mFee;
        public AmountAndFee(long amt, long fee) {
            mAmount = amt;
            mFee = fee;
        }
    }

    public long amountForAccount(WalletTransaction wtx) {
        return mHDReceiver.amountForAccount(wtx);
    }

    public long balanceForAccount() {
        return mHDReceiver.balanceForAccount();
    }

    public long availableForAccount() {
        return mHDReceiver.availableForAccount();
    }

    private void setState(State newstate) {
        // SHUTDOWN is final ...
        if (mState == State.SHUTDOWN)
            return;
        mLogger.info("setState " + getStateString());
        mState = newstate;
        sendStateChanged();
    }

    private void sendStateChanged() {
        Intent intent = new Intent("wallet-state-changed");
        mLBM.sendBroadcast(intent);
    }

    public void sweepKey(ECKey key, long fee,
                         int accountId, JSONArray outputs) {
        mLogger.info("sweepKey starting");

        mLogger.info("key addr " + key.toAddress(mParams).toString());

        Transaction tx = new Transaction(mParams);

        long balance = 0;
        ArrayList<Script> scripts = new ArrayList<Script>();
        try {
            for (int ii = 0; ii < outputs.length(); ++ii) {
                JSONObject output;
				output = outputs.getJSONObject(ii);

                String tx_hash = output.getString("tx_hash");
                int tx_output_n = output.getInt("tx_output_n");
                String script = output.getString("script");

                // Reverse byte order, create hash.
                Sha256Hash hash =
                    new Sha256Hash(WalletUtil.msgHexToBytes(tx_hash));
            
                tx.addInput(new TransactionInput
                            (mParams, tx, new byte[]{},
                             new TransactionOutPoint(mParams,
                                                     tx_output_n, hash)));

                scripts.add(new Script(Hex.decode(script)));
                    
                balance += output.getLong("value");
            }
        } catch (JSONException e) {
            e.printStackTrace();
            throw new RuntimeException("trouble parsing unspent outputs");
        }

        // Compute balance - fee.
        long amount = balance - fee;
        mLogger.info(String.format("sweeping %d", amount));

        // Figure out the destination address.
        Address to = mHDReceiver.nextReceiveAddress();
        mLogger.info("sweeping to " + to.toString());

        // Add output.
        tx.addOutput(BigInteger.valueOf(amount), to);

        WalletUtil.signTransactionInputs(tx, Transaction.SigHash.ALL,
                                         key, scripts);

        mLogger.info("tx bytes: " +
                     new String(Hex.encode(tx.bitcoinSerialize())));

        // mKit.peerGroup().broadcastTransaction(tx);
        broadcastTransaction(mKit.peerGroup(), tx);

        mLogger.info("sweepKey finished");
    }

    public void broadcastTransaction(final TransactionBroadcaster broadcaster,
                                     final Transaction tx) {
        new Thread() {
            @Override
            public void run() {
                // Handle the future results just for logging.
                try {
                    Futures.addCallback(broadcaster.broadcastTransaction(tx),
                                        new FutureCallback<Transaction>() {
                        @Override
                        public void onSuccess(Transaction transaction) {
                            mLogger.info("Successfully broadcast sweep tx: {}",
                                         transaction);
                        }

                        @Override
                        public void onFailure(Throwable throwable) {
                            mLogger.error("Failed to broadcast sweep tx",
                                          throwable);
                        }
                    });
                } catch (Exception e) {
                    mLogger.error("Failed to broadcast sweep tx", e);
                }
            }
        }.start();
    }
}

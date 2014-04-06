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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.DataLengthException;
import org.spongycastle.crypto.InvalidCipherTextException;
import org.spongycastle.crypto.params.KeyParameter;

import android.content.Context;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.ScriptException;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionConfidence;
import com.google.bitcoin.core.TransactionConfidence.ConfidenceType;
import com.google.bitcoin.core.TransactionInput;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.crypto.KeyCrypter;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.wallet.WalletTransaction;

public class HDReceiver {

    private static Logger mLogger = LoggerFactory.getLogger(HDReceiver.class);

    private NetworkParameters	mParams;
    private File				mDirectory;
    private String				mFilePrefix;

    private DeterministicKey	mAccountKey = null;

    private HDAccount			mAccount;

    public static String persistPath(String filePrefix) {
        return filePrefix + ".hdreceive";
    }

    // Create an HDReceiver from persisted file data.
    public static HDReceiver restore(Context ctxt,
                                     NetworkParameters params,
                                     File directory,
                                     String filePrefix,
                                     KeyCrypter keyCrypter,
                                     KeyParameter aesKey)
        throws InvalidCipherTextException, IOException {

        try {
            JSONObject node = deserialize(directory, filePrefix);

            return new HDReceiver(ctxt, params, directory, filePrefix, node);
        }
        catch (JSONException ex) {
            String msg = "trouble deserializing wallet: " + ex.toString();

            // Have to break the message into chunks for big messages ...
            while (msg.length() > 1024) {
                String chunk = msg.substring(0, 1024);
                mLogger.error(chunk);
                msg = msg.substring(1024);
            }
            mLogger.error(msg);

            throw new RuntimeException(msg);
        }
    }

    // Deserialize the wallet data.
    public static JSONObject deserialize(File dir, String prefix)
        throws IOException, JSONException {

        String path = persistPath(prefix);
        mLogger.info("restoring HDReceiver from " + path);
        try {
            File file = new File(dir, path);
            int len = (int) file.length();

            BufferedReader br = null;
            StringBuilder sb = new StringBuilder();
            try {
                String currentLine;
                br = new BufferedReader(new FileReader(file));
                while ((currentLine = br.readLine()) != null)
                    sb.append(currentLine);
            }
            finally {
                try {
                    if (br != null)
                        br.close();
                }
                catch (IOException ex) {
                    String msg = "problem closing file: " + ex.toString();
                    mLogger.error(msg);
                    throw new RuntimeException(msg);
                }
            }

            String jsonstr = sb.toString();

            /*
            String msg = jsonstr;
            while (msg.length() > 1024) {
                String chunk = msg.substring(0, 1024);
                mLogger.error(chunk);
                msg = msg.substring(1024);
            }
            mLogger.error(msg);
            */
            
            JSONObject node = new JSONObject(jsonstr);
            return node;

        } catch (IOException ex) {
            mLogger.warn("trouble reading " + path + ": " + ex.toString());
            throw ex;
        } catch (RuntimeException ex) {
            mLogger.warn("trouble restoring wallet: " + ex.toString());
            throw ex;
        }
    }

    // This signature is used when the receiver is deserialized.
    public HDReceiver(Context ctxt,
                      NetworkParameters params,
                      File dir,
                      String prefix,
                      JSONObject node) {

        mParams = params;
        mDirectory = dir;
        mFilePrefix = prefix;

        try {
            String xpubstr = node.getString("xpub");
            mAccountKey = WalletUtil.createMasterPubKeyFromPubB58(xpubstr);

            JSONObject acctNode = node.getJSONObject("account");
            mAccount = new HDAccount(params, mAccountKey, acctNode);

            mLogger.info("deserialized HDReceiver");
        }
        catch (Exception ex) {
            // Shouldn't happen ...
            ex.printStackTrace();
            String msg = "unexpected exception: " + ex.toString();
            mLogger.error(msg);
            throw new RuntimeException(msg);
        }
    }

    // This signature is used when the xpub is imported.
    public HDReceiver(Context ctxt,
                      NetworkParameters params,
                      File dir,
                      String prefix,
                      DeterministicKey accountRootKey) {

        mParams = params;
        mDirectory = dir;
        mFilePrefix = prefix;

        mAccountKey = accountRootKey;
        mAccount = new HDAccount(params, mAccountKey, "Account 0");
        mLogger.info("created HDReceiver");
    }

    public JSONObject dumps() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("xpub", mAccount.xpubstr());
            obj.put("account", mAccount.dumps());
        return obj;
        }
        catch (JSONException ex) {
            throw new RuntimeException(ex);	// Shouldn't happen.
        }
    }

    public HDAccount getAccount() {
    	return mAccount;
    }
    
    public void gatherAllKeys(long creationTime, List<ECKey> keys) {
        mAccount.gatherAllKeys(null, null, creationTime, keys);
    }

    public void clearBalances() {
        // Clears the balance and tx counters.
        mAccount.clearBalance();
    }

    public void applyAllTransactions(Iterable<WalletTransaction> iwt) {
        // Clear the balance and tx counters.
        clearBalances();

        for (WalletTransaction wtx : iwt) {
            // WalletTransaction.Pool pool = wtx.getPool();
            Transaction tx = wtx.getTransaction();
            boolean avail = !tx.isPending();
            TransactionConfidence conf = tx.getConfidence();
            ConfidenceType ct = conf.getConfidenceType();

            // Skip dead transactions.
            if (ct != ConfidenceType.DEAD) {

                // Traverse the HDAccounts with all outputs.
                List<TransactionOutput> lto = tx.getOutputs();
                for (TransactionOutput to : lto) {
                    long value = to.getValue().longValue();
                    try {
                        byte[] pubkey = null;
                        byte[] pubkeyhash = null;
                        Script script = to.getScriptPubKey();
                        if (script.isSentToRawPubKey())
                            pubkey = script.getPubKey();
                        else
                            pubkeyhash = script.getPubKeyHash();
                        mAccount.applyOutput(pubkey, pubkeyhash, value, avail);
                    } catch (ScriptException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }

                // Traverse the HDAccounts with all inputs.
                List<TransactionInput> lti = tx.getInputs();
                for (TransactionInput ti : lti) {
                    // Get the connected TransactionOutput to see value.
                    TransactionOutput cto = ti.getConnectedOutput();
                    if (cto == null) {
                        // It appears we land here when processing transactions
                        // where we handled the output above.
                        //
                        // mLogger.warn("couldn't find connected output for input");
                        continue;
                    }
                    long value = cto.getValue().longValue();
                    try {
                        byte[] pubkey = ti.getScriptSig().getPubKey();
                        mAccount.applyInput(pubkey, value);
                    } catch (ScriptException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }
        }

        // This is too noisy
        // // Log balance summary.
        // for (HDAccount acct : mAccounts)
        //     acct.logBalance();
    }

    public long balanceForAccount() {
    	return mAccount.balance();
    }

    public long availableForAccount() {
    	return mAccount.available();
    }

    public long amountForAccount(WalletTransaction wtx, int acctnum) {

        // This routine is only called from the View Transactions
        // activity, so it is OK if it uses all balance and not
        // available balance (since the confirmation count is shown).

        long credits = 0;
        long debits = 0;

        // Which accounts are we considering?  (-1 means all)
        ArrayList<HDAccount> accts = new ArrayList<HDAccount>();
        accts.add(mAccount);
        
        Transaction tx = wtx.getTransaction();

        // Consider credits.
        List<TransactionOutput> lto = tx.getOutputs();
        for (TransactionOutput to : lto) {
            long value = to.getValue().longValue();
            try {
                byte[] pubkey = null;
                byte[] pubkeyhash = null;
                Script script = to.getScriptPubKey();
                if (script.isSentToRawPubKey())
                    pubkey = script.getPubKey();
                else
                    pubkeyhash = script.getPubKeyHash();
                for (HDAccount hda : accts) {
                    if (hda.hasPubKey(pubkey, pubkeyhash))
                        credits += value;
                }
            } catch (ScriptException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        // Traverse the HDAccounts with all inputs.
        List<TransactionInput> lti = tx.getInputs();
        for (TransactionInput ti : lti) {
            // Get the connected TransactionOutput to see value.
            TransactionOutput cto = ti.getConnectedOutput();
            if (cto == null) {
                // It appears we land here when processing transactions
                // where we handled the output above.
                //
                // mLogger.warn("couldn't find connected output for input");
                continue;
            }
            long value = cto.getValue().longValue();
            try {
                byte[] pubkey = ti.getScriptSig().getPubKey();
                for (HDAccount hda : accts)
                    if (hda.hasPubKey(pubkey, null))
                        debits += value;
            } catch (ScriptException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        return credits - debits;
    }

    public Address nextReceiveAddress() {
        HDAccount acct = mAccount;
        return acct.nextReceiveAddress();
    }

    public void persist() {
        String path = persistPath(mFilePrefix);
        String tmpPath = path + ".tmp";
        try {
            // Serialize into a byte array.
            JSONObject jsonobj = dumps();
            String jsonstr = jsonobj.toString(4);	// indentation
            byte[] plainBytes = jsonstr.getBytes(Charset.forName("UTF-8"));

            // Ready a tmp file.
            File tmpFile = new File(mDirectory, tmpPath);
            if (tmpFile.exists())
                tmpFile.delete();

			FileOutputStream ostrm = new FileOutputStream(tmpFile);
            ostrm.write(plainBytes);
			ostrm.close();

            // Swap the tmp file into place.
            File newFile = new File(mDirectory, path);
            if (!tmpFile.renameTo(newFile))
                mLogger.warn("failed to rename to " + newFile);
            else
                mLogger.info("persisted to " + path);

        } catch (JSONException ex) {
            mLogger.warn("failed generating JSON: " + ex.toString());
        } catch (IOException ex) {
            mLogger.warn("failed to write to " + tmpPath + ": " +
                         ex.toString());
		} catch (DataLengthException ex) {
            mLogger.warn("encryption failed: " + ex.toString());
		} catch (IllegalStateException ex) {
            mLogger.warn("encryption failed: " + ex.toString());
		}
    }

    // Ensure that there are enough spare addresses on all chains.
    // Returns the most number of addresses added to a chain.
    public int ensureMargins(Wallet wallet) {
    	return mAccount.ensureMargins(wallet, null, null);
    }

    public Balance getBalance() {
        return new Balance(0,
                           mAccount.getName(),
                           mAccount.balance(),
                           mAccount.available());
    }

    // Finds an address (if present) and returns a description
    // of it's wallet location.
    public HDAddressDescription findAddress(Address addr) {
    	return mAccount.findAddress(addr);
    }
}

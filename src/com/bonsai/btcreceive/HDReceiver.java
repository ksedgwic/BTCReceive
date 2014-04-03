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

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.BufferedBlockCipher;
import org.spongycastle.crypto.DataLengthException;
import org.spongycastle.crypto.InvalidCipherTextException;
import org.spongycastle.crypto.engines.AESFastEngine;
import org.spongycastle.crypto.modes.CBCBlockCipher;
import org.spongycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.spongycastle.crypto.params.KeyParameter;
import org.spongycastle.crypto.params.ParametersWithIV;

import android.content.Context;

import com.bonsai.btcreceive.WalletService.AmountAndFee;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.Base58;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.InsufficientMoneyException;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.ScriptException;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionConfidence;
import com.google.bitcoin.core.TransactionConfidence.ConfidenceType;
import com.google.bitcoin.core.TransactionInput;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.Wallet.SendRequest;
import com.google.bitcoin.crypto.ChildNumber;
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.crypto.HDKeyDerivation;
import com.google.bitcoin.crypto.KeyCrypter;
import com.google.bitcoin.crypto.KeyCrypterScrypt;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.wallet.WalletTransaction;

public class HDReceiver {

    private static Logger mLogger = LoggerFactory.getLogger(HDReceiver.class);

    private final NetworkParameters	mParams;
    private final File				mDirectory;
    private final String			mFilePrefix;
    private KeyCrypter				mKeyCrypter;
    private KeyParameter			mAesKey;

    private final DeterministicKey	mMasterKey = null;
    private final DeterministicKey	mWalletRoot = null;

    private final byte[]			mWalletSeed = null;

    private HDAccount				mAccount;

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
            JSONObject node = deserialize(directory, filePrefix,
                                          keyCrypter, aesKey);

            return new HDReceiver(ctxt, params, directory, filePrefix,
                                keyCrypter, aesKey, node, false);
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
    public static JSONObject deserialize(File directory,
                                         String filePrefix,
                                         KeyCrypter keyCrypter,
                                         KeyParameter aesKey)
        throws IOException, InvalidCipherTextException, JSONException {

        String path = persistPath(filePrefix);
        mLogger.info("restoring HDReceiver from " + path);
        try {
            File file = new File(directory, path);
            int len = (int) file.length();

            // Open persisted file.
            DataInputStream dis =
                new DataInputStream(new FileInputStream(file));

            // Read IV from file.
            byte[] iv = new byte[KeyCrypterScrypt.BLOCK_LENGTH];
			dis.readFully(iv);

            // Read the ciphertext from the file.
            byte[] cipherBytes = new byte[len - iv.length];
            dis.readFully(cipherBytes);
            dis.close();

            // Decrypt the ciphertext.
            ParametersWithIV keyWithIv =
                new ParametersWithIV(new KeyParameter(aesKey.getKey()), iv);
            BufferedBlockCipher cipher =
                new PaddedBufferedBlockCipher
                (new CBCBlockCipher(new AESFastEngine()));
            cipher.init(false, keyWithIv);
            int minimumSize = cipher.getOutputSize(cipherBytes.length);
            byte[] outputBuffer = new byte[minimumSize];
            int length1 = cipher.processBytes(cipherBytes,
                                              0, cipherBytes.length,
                                              outputBuffer, 0);
            int length2 = cipher.doFinal(outputBuffer, length1);
            int actualLength = length1 + length2;
            byte[] decryptedBytes = new byte[actualLength];
            System.arraycopy(outputBuffer, 0, decryptedBytes, 0, actualLength);
            
            // Parse the decryptedBytes.
            String jsonstr = new String(decryptedBytes);

            /*
            // THIS CONTAINS THE SEED!
            // Have to break the message into chunks for big messages ...
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
        } catch (InvalidCipherTextException ex) {
            mLogger.warn("wallet decrypt failed: " + ex.toString());
            throw ex;
		}
    }

    public HDReceiver(Context ctxt,
                    NetworkParameters params,
                    File directory,
                    String filePrefix,
                    KeyCrypter keyCrypter,
                    KeyParameter aesKey,
                    JSONObject walletNode,
                    boolean isPairing) throws JSONException {

        mParams = params;
        mDirectory = directory;
        mFilePrefix = filePrefix;
        mKeyCrypter = keyCrypter;
        mAesKey = aesKey;

//        try {
//            mWalletSeed = Base58.decode(walletNode.getString("seed"));
//
//            if (!walletNode.has("bip39_version")) {
//                mBIP39Version = MnemonicCodeX.Version.V0_5;
//                mLogger.info("defaulting BIP39 version to V0_5");
//            } else {
//                String bipverstr = walletNode.getString("bip39_version");
//                if (bipverstr.equals("V0_5")) {
//                    mBIP39Version = MnemonicCodeX.Version.V0_5;
//                    mLogger.info("setting BIP39 version to V0_5");
//                }
//                else if (bipverstr.equals("V0_6")) {
//                    mBIP39Version = MnemonicCodeX.Version.V0_6;
//                    mLogger.info("setting BIP39 version to V0_6");
//                }
//                else
//                {
//                    throw new RuntimeException
//                        ("unknown BIP39 version: " + bipverstr);
//                }
//            }
//
//            if (!walletNode.has("acct_derive")) {
//                mHDStructVersion = HDStructVersion.HDSV_L0PUB;
//                mLogger.info("defaulting mHDStructVersion to HDSV_L0PUB");
//            } else {
//                String acctderivstr = walletNode.getString("acct_derive");
//                if (acctderivstr.equals("PRV")) {
//                    mHDStructVersion = HDStructVersion.HDSV_L0PRV;
//                    mLogger.info("setting mHDStructVersion to HDSV_L0PRV");
//                } else if (acctderivstr.equals("PUB")) {
//                    mHDStructVersion = HDStructVersion.HDSV_L0PUB;
//                    mLogger.info("setting mHDStructVersion to HDSV_L0PUB");
//                } else if (acctderivstr.equals("STDV0")) {
//                    mHDStructVersion = HDStructVersion.HDSV_STDV0;
//                    mLogger.info("setting mHDStructVersion to HDSV_STDV0");
//                } else {
//                    throw new RuntimeException
//                        ("unknown acct_derive value: " + acctderivstr);
//                }
//            }
//
//        } catch (AddressFormatException e) {
//            throw new RuntimeException("couldn't decode wallet seed");
//        }
//
//        byte[] hdseed;
//        try {
//            InputStream wis = ctxt.getAssets().open("wordlist/english.txt");
//            MnemonicCodeX mc =
//                new MnemonicCodeX(wis, MnemonicCodeX.BIP39_ENGLISH_SHA256);
//            List<String> wordlist = mc.toMnemonic(mWalletSeed);
//            hdseed = MnemonicCodeX.toSeed(wordlist, "", mBIP39Version);
//        } catch (Exception ex) {
//            throw new RuntimeException("trouble decoding seed");
//        }
//
//        mMasterKey = HDKeyDerivation.createMasterPrivateKey(hdseed);
//
//        switch (mHDStructVersion) {
//        case HDSV_L0PUB:
//        case HDSV_L0PRV:
//            // Both of the level 0 derivations use the master as the
//            // root of the accounts.
//            mWalletRoot = mMasterKey;
//            break;
//        case HDSV_STDV0:
//            // Standard derivation starts from M/0/0'
//            DeterministicKey t0 =
//                HDKeyDerivation.deriveChildKey(mMasterKey, 0);
//            mWalletRoot =
//                HDKeyDerivation.deriveChildKey(t0, ChildNumber.PRIV_BIT);
//            break;
//        default:
//            throw new RuntimeException("invalid HDStructVersion");
//        }
//
//        mLogger.info("restoring HDReceiver " + mWalletRoot.getPath());
//
//        mAccounts = new ArrayList<HDAccount>();
//        JSONArray accounts = walletNode.getJSONArray("accounts");
//        for (int ii = 0; ii < accounts.length(); ++ii) {
//            mLogger.info(String.format("deserializing account %d", ii));
//            JSONObject acctNode = accounts.getJSONObject(ii);
//            mAccounts.add(new HDAccount(mParams, mWalletRoot,
//                                        acctNode, isPairing,
//                                        mHDStructVersion));
//        }
    }

    public JSONObject dumps(boolean isPairing) {
		return null;
//        try {
//            JSONObject obj = new JSONObject();
//
//            obj.put("seed", Base58.encode(mWalletSeed));
//            switch (mBIP39Version) {
//            case V0_5:
//                obj.put("bip39_version", "V0_5");
//                break;
//            case V0_6:
//                obj.put("bip39_version", "V0_6");
//                break;
//            default:
//                throw new RuntimeException("unknown BIP39 version");
//            }
//
//            switch (mHDStructVersion) {
//            case HDSV_L0PUB:
//                obj.put("acct_derive", "PUB");
//                break;
//            case HDSV_L0PRV:
//                obj.put("acct_derive", "PRV");
//                break;
//            case HDSV_STDV0:
//                obj.put("acct_derive", "STDV0");
//                break;
//            }
//
//            JSONArray accts = new JSONArray();
//            for (HDAccount acct : mAccounts)
//                accts.put(acct.dumps(isPairing));
//
//            obj.put("accounts", accts);
//
//            return obj;
//        }
//        catch (JSONException ex) {
//            throw new RuntimeException(ex);	// Shouldn't happen.
//        }
    }

    public HDReceiver(Context ctxt,
                    NetworkParameters params,
                    File directory,
                    String filePrefix,
                    KeyCrypter keyCrypter,
                    KeyParameter aesKey,
                    byte[] walletSeed,
                    int numAccounts) {
        mParams = params;
        mDirectory = directory;
        mFilePrefix = filePrefix;
        mKeyCrypter = keyCrypter;
        mAesKey = aesKey;
        
//        switch (mBIP39Version) {
//        case V0_5:
//            mLogger.info("BIP39 version V0_5");
//            break;
//        case V0_6:
//            mLogger.info("BIP39 version V0_6");
//            break;
//        default:
//            throw new RuntimeException("unknown BIP39 version");
//        }
//
//        byte[] hdseed;
//        try {
//            InputStream wis = ctxt.getAssets().open("wordlist/english.txt");
//            MnemonicCodeX mc =
//                new MnemonicCodeX(wis, MnemonicCodeX.BIP39_ENGLISH_SHA256);
//            List<String> wordlist = mc.toMnemonic(mWalletSeed);
//            hdseed = MnemonicCodeX.toSeed(wordlist, "", mBIP39Version);
//        } catch (Exception ex) {
//            throw new RuntimeException("trouble decoding seed");
//        }
//
//        mMasterKey = HDKeyDerivation.createMasterPrivateKey(hdseed);
//
//        switch (mHDStructVersion) {
//        case HDSV_L0PUB:
//        case HDSV_L0PRV:
//            // Both of the level 0 derivations use the master as the
//            // root of the accounts.
//            mWalletRoot = mMasterKey;
//            break;
//        case HDSV_STDV0:
//            // Standard derivation starts from M/0/0'
//            DeterministicKey t0 =
//                HDKeyDerivation.deriveChildKey(mMasterKey, 0);
//            mWalletRoot =
//                HDKeyDerivation.deriveChildKey(t0, ChildNumber.PRIV_BIT);
//            break;
//        default:
//            throw new RuntimeException("invalid HDStructVersion");
//        }
//
//        mLogger.info("created HDReceiver " + mWalletRoot.getPath());
//
//        // Add some accounts.
//        mAccounts = new ArrayList<HDAccount>();
//        for (int ii = 0; ii < numAccounts; ++ii) {
//            String acctName = String.format("Account %d", ii);
//            mAccounts.add(new HDAccount(mParams, mWalletRoot, acctName, ii,
//                                        mHDStructVersion));
//        }
    }

    public void setPersistCrypter(KeyCrypter keyCrypter, KeyParameter aesKey) {
        mKeyCrypter = keyCrypter;
        mAesKey = aesKey;
    }

    public byte[] getWalletSeed() {
        return mWalletSeed;
    }

    public HDAccount getAccount() {
    	return mAccount;
    }
    
    public void gatherAllKeys(long creationTime, List<ECKey> keys) {
        mAccount.gatherAllKeys(mKeyCrypter, mAesKey, creationTime, keys);
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

    public void getBalances(List<Balance> balances) {
    	HDAccount acct = mAccount;
        balances.add(new Balance(acct.getId(),
                                     acct.getName(),
                                     acct.balance(),
                                     acct.available()));
    }

    public Address nextReceiveAddress() {
        HDAccount acct = mAccount;
        return acct.nextReceiveAddress();
    }

    public void sendAccountCoins(Wallet wallet,
                                 int acctnum,
                                 Address dest,
                                 long value,
                                 long fee) throws RuntimeException {

        HDAccount acct = mAccount;

        SendRequest req = SendRequest.to(dest, BigInteger.valueOf(value));
        req.fee = BigInteger.valueOf(fee);
        req.feePerKb = BigInteger.ZERO;
        req.ensureMinRequiredFee = false;
        req.changeAddress = acct.nextChangeAddress();
        req.coinSelector = acct.coinSelector();
        req.aesKey = mAesKey;

		try {
			wallet.sendCoins(req);
		} catch (InsufficientMoneyException e) {
            throw new RuntimeException("Not enough BTC in account");
		}
    }


    public void persist() {
//        String path = persistPath(mFilePrefix);
//        String tmpPath = path + ".tmp";
//        try {
//            // Serialize into a byte array.
//            JSONObject jsonobj = dumps(false);
//            String jsonstr = jsonobj.toString(4);	// indentation
//            byte[] plainBytes = jsonstr.getBytes(Charset.forName("UTF-8"));
//
//            // Generate an IV.
//            byte[] iv = new byte[KeyCrypterScrypt.BLOCK_LENGTH];
//            secureRandom.nextBytes(iv);
//
//            // Encrypt the serialized data.
//            ParametersWithIV keyWithIv = new ParametersWithIV(mAesKey, iv);
//            BufferedBlockCipher cipher =
//                new PaddedBufferedBlockCipher
//                (new CBCBlockCipher(new AESFastEngine()));
//            cipher.init(true, keyWithIv);
//            byte[] encryptedBytes =
//                new byte[cipher.getOutputSize(plainBytes.length)];
//            int length = cipher.processBytes(plainBytes, 0, plainBytes.length,
//                                             encryptedBytes, 0);
//            cipher.doFinal(encryptedBytes, length);
//
//            // Ready a tmp file.
//            File tmpFile = new File(mDirectory, tmpPath);
//            if (tmpFile.exists())
//                tmpFile.delete();
//
//            // Write the IV followed by the data.
//			FileOutputStream ostrm = new FileOutputStream(tmpFile);
//			ostrm.write(iv);
//            ostrm.write(encryptedBytes);
//			ostrm.close();
//
//            // Swap the tmp file into place.
//            File newFile = new File(mDirectory, path);
//            if (!tmpFile.renameTo(newFile))
//                mLogger.warn("failed to rename to " + newFile);
//            else
//                mLogger.info("persisted to " + path);
//
//        } catch (JSONException ex) {
//            mLogger.warn("failed generating JSON: " + ex.toString());
//        } catch (IOException ex) {
//            mLogger.warn("failed to write to " + tmpPath + ": " + ex.toString());
//		} catch (DataLengthException ex) {
//            mLogger.warn("encryption failed: " + ex.toString());
//		} catch (IllegalStateException ex) {
//            mLogger.warn("encryption failed: " + ex.toString());
//		} catch (InvalidCipherTextException ex) {
//            mLogger.warn("encryption failed: " + ex.toString());
//		}
    }

    // Ensure that there are enough spare addresses on all chains.
    // Returns the most number of addresses added to a chain.
    public int ensureMargins(Wallet wallet) {
    	return mAccount.ensureMargins(wallet, mKeyCrypter, mAesKey);
    }

    // Finds an address (if present) and returns a description
    // of it's wallet location.
    public HDAddressDescription findAddress(Address addr) {
    	return mAccount.findAddress(addr);
    }
}

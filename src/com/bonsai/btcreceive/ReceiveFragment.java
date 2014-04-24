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

import java.math.BigInteger;
import java.util.Hashtable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.uri.BitcoinURI;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

public class ReceiveFragment extends Fragment {

    private static Logger mLogger =
        LoggerFactory.getLogger(ReceiveFragment.class);

    protected LocalBroadcastManager mLBM;

    private BaseWalletActivity mBase;

	private final static QRCodeWriter sQRCodeWriter = new QRCodeWriter();

    protected EditText mBTCAmountEditText = null;
    protected EditText mFiatAmountEditText = null;
    protected boolean mUserSetAmountFiat;

    protected boolean mValueSet = false;

    // In order to automatically transition to the transaction view
    // when the address receives funds we need to watch the address
    // and remember if we've already transitioned.
    protected HDAddress mHDAddress = null;
    protected boolean mTransitioned = false;

	@Override
	public void onCreate(Bundle savedInstanceState) {
        mLogger.info("ReceiveFragment onCreate");
		super.onCreate(savedInstanceState);
        mLBM = LocalBroadcastManager.getInstance(getActivity());
        mBase = (BaseWalletActivity) getActivity();
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
        mLogger.info("ReceiveFragment onActivityCreated");
		super.onActivityCreated(savedInstanceState);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        mLogger.info("ReceiveFragment onCreateView");
		View view =
            inflater.inflate(R.layout.receive_fragment, container, false);

        // Start off presuming the user set the Fiat amount.
        mUserSetAmountFiat = true;

		return view;
	}

	@Override
	public void onResume() {
        mLogger.info("ReceiveFragment onResume");
        super.onResume();

        mLBM.registerReceiver(mWalletStateChangedReceiver,
                              new IntentFilter("wallet-state-changed"));

        mBTCAmountEditText =
            (EditText) getActivity().findViewById(R.id.receive_btc_amount);
        mFiatAmountEditText =
            (EditText) getActivity().findViewById(R.id.receive_fiat_amount);

        mBTCAmountEditText.addTextChangedListener(mBTCAmountWatcher);
        mFiatAmountEditText.addTextChangedListener(mFiatAmountWatcher);

        mBTCAmountEditText.setOnEditorActionListener(checkForDone);
        mFiatAmountEditText.setOnEditorActionListener(checkForDone);

        mBTCAmountEditText.setOnTouchListener(touchListener);
        mFiatAmountEditText.setOnTouchListener(touchListener);

        if (mValueSet)
            showAddress();
        else
            hideAddress();

        BTCFmt btcfmt = mBase.getBTCFmt();

        // Set these each time we resume in case we've visited the
        // Settings and they've changed.
        {
            TextView tv =
                (TextView) getActivity().findViewById(R.id.receive_btc_label);
            tv.setText(btcfmt.unitStr());
        }
    }

    @Override
	public void onPause() {
        mLogger.info("ReceiveFragment onPause");
        mLBM.unregisterReceiver(mWalletStateChangedReceiver);
        super.onPause();
    }

    private BroadcastReceiver mWalletStateChangedReceiver =
        new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Transition once to the transactions view if the
                // address in question receives a transaction.

                // Do we currently have an address to watch?
                if (mHDAddress == null)
                    return;

                // Have we already fired?
                if (mTransitioned)
                    return;

                // Have there been any transactions?
                if (mHDAddress.numTrans() == 0)
                    return;

                mTransitioned = true;

                // Take down the address.
                hideAddress();
                mValueSet = false;
                mBTCAmountEditText.setText("");
                mFiatAmountEditText.setText("");
                maybeShowKeyboard();

                // Transition to the transactions list.
                MainActivity main = (MainActivity) getActivity();
                main.setPagerItem(1);
            }
        };

    public void maybeShowKeyboard() {
        // Called by our parent when it would be good for us to
        // bring up the keyboard.

        mLogger.info("maybeShowKeyboard starting");

        // If the user has the value set already we don't want the
        // keyboard.
        if (mValueSet)
            return;

        Activity activity = getActivity();
        InputMethodManager imm =
            (InputMethodManager) activity.getSystemService
            (Context.INPUT_METHOD_SERVICE);

        if (mUserSetAmountFiat && mFiatAmountEditText != null) {
            mLogger.info("maybeShowKeyboard fiat");
            imm.showSoftInput(mFiatAmountEditText,
                              InputMethodManager.SHOW_IMPLICIT);
            mFiatAmountEditText.requestFocus();
        }
        else if (mBTCAmountEditText != null) {
            mLogger.info("maybeShowKeyboard btc");
            imm.showSoftInput(mBTCAmountEditText,
                              InputMethodManager.SHOW_IMPLICIT);
            mBTCAmountEditText.requestFocus();
        }
    }

    // NOTE - This code implements a pair of "cross updating" fields.
    // If the user changes the BTC amount the fiat field is constantly
    // updated at the current mFiatPerBTC rate.  If the user changes
    // the fiat field the BTC field is constantly updated at the
    // current rate.

    private final TextWatcher mBTCAmountWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence ss,
                                          int start,
                                          int count,
                                          int after) {
                // Note that the user changed the BTC last.
                mUserSetAmountFiat = false;
            }

            @Override
            public void onTextChanged(CharSequence ss,
                                      int start,
                                      int before,
                                      int count) {}

			@Override
            public void afterTextChanged(Editable ss) {
                updateAmountFields();
            }

        };

    private final TextWatcher mFiatAmountWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence ss,
                                          int start,
                                          int count,
                                          int after) {
                mUserSetAmountFiat = true;
            }

            @Override
            public void onTextChanged(CharSequence ss,
                                      int start,
                                      int before,
                                      int count) {}

			@Override
            public void afterTextChanged(Editable ss) {
                updateAmountFields();
            }
        };

	protected void updateAmountFields() {
        BTCFmt btcfmt = mBase.getBTCFmt();
        double fiatPerBTC = mBase.fiatPerBTC();

        // Which field did the user last edit?
        if (mUserSetAmountFiat) {
            // The user set the Fiat amount.
            String ss = mFiatAmountEditText.getText().toString();

            // Avoid recursion by removing the other fields listener.
            mBTCAmountEditText.removeTextChangedListener
                (mBTCAmountWatcher);

            String bbs;
            try {
                double ff = parseNumberWorkaround(ss.toString());
                long bb;
                if (fiatPerBTC == 0.0) {
                    bbs = "";
                }
                else {
                    bb = btcfmt.btcAtRate(ff, fiatPerBTC);
                    bbs = btcfmt.format(bb);
                }
            } catch (final NumberFormatException ex) {
                bbs = "";
            }
            mBTCAmountEditText.setText(bbs, TextView.BufferType.EDITABLE);

            // Restore the other fields listener.
            mBTCAmountEditText.addTextChangedListener(mBTCAmountWatcher);
        } else {
            // The user set the BTC amount.
            String ss = mBTCAmountEditText.getText().toString();

            // Avoid recursion by removing the other fields listener.
            mFiatAmountEditText.removeTextChangedListener
                (mFiatAmountWatcher);

            String ffs;
            try {
                long bb = btcfmt.parse(ss.toString());
                double ff = btcfmt.fiatAtRate(bb, fiatPerBTC);
                ffs = String.format("%.2f", ff);
            } catch (final NumberFormatException ex) {
                ffs = "";
            }
            mFiatAmountEditText.setText(ffs, TextView.BufferType.EDITABLE);

            // Restore the other fields listener.
            mFiatAmountEditText.addTextChangedListener(mFiatAmountWatcher);
        }
    }

    private View.OnTouchListener touchListener =
        new View.OnTouchListener() {
                public boolean onTouch(View vv, MotionEvent event) {
                    if (mValueSet) {
                        hideAddress();	// User wants to change the amount.
                        mValueSet = false;
                    }
					return false;
                }
            };

    private TextView.OnEditorActionListener checkForDone =
        new TextView.OnEditorActionListener() {
                public boolean onEditorAction(TextView view,
                                              int actionId,
                                              KeyEvent event) {
                    mLogger.info("onEditorAction");
                    mValueSet = true;
                    showAddress();
                    return false;	// so it will take down the keyboard.
                }
            };

    public void showAddress() {
        BTCFmt btcfmt = mBase.getBTCFmt();

        Address addr = mBase.getWalletService().nextReceiveAddress();
        String addrstr = addr.toString();

        TextView addrtv =
            (TextView) getActivity().findViewById(R.id.receive_addr);
        addrtv.setText(addrstr);
        addrtv.setVisibility(View.VISIBLE);

        String ss = mBTCAmountEditText.getText().toString();
        long bb = btcfmt.parse(ss.toString());
        BigInteger amt = bb == 0 ? null : BigInteger.valueOf(bb);

        String uri = BitcoinURI.convertToBitcoinURI(addrstr, amt, null, null);

        mLogger.info("view address uri=" + uri);

        final int size =
            (int) (240 * getResources().getDisplayMetrics().density);

        // Load the QR bitmap.
        Bitmap bm = createBitmap(uri, size);
        if (bm != null) {
            ImageView iv =
                (ImageView) getActivity().findViewById(R.id.receive_qr_view);
            iv.setImageBitmap(bm);
            iv.setVisibility(View.VISIBLE);
        }

        // Find the HDAddress object associated with this address.
        HDAddressDescription addrdesc =
            mBase.getWalletService().findAddress(addr);
        mHDAddress = addrdesc.hdAddress;
        mTransitioned = false;
    }

    public void hideAddress() {
        TextView addrtv =
            (TextView) getActivity().findViewById(R.id.receive_addr);
        addrtv.setVisibility(View.GONE);

        ImageView iv =
            (ImageView) getActivity().findViewById(R.id.receive_qr_view);
        iv.setVisibility(View.GONE);

        // Clear the HDAddress associated with this address.
        mHDAddress = null;
        mTransitioned = false;
    }

    private Bitmap createBitmap(String content, final int size) {
        final Hashtable<EncodeHintType, Object> hints =
            new Hashtable<EncodeHintType, Object>();
        hints.put(EncodeHintType.MARGIN, 0);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        BitMatrix result;
		try {
			result = sQRCodeWriter.encode(content,
                                          BarcodeFormat.QR_CODE,
                                          size,
                                          size,
                                          hints);
		} catch (WriterException ex) {
            mLogger.warn("qr encoder failed: " + ex.toString());
            return null;
		}

        final int width = result.getWidth();
        final int height = result.getHeight();
        final int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++)
        {
            final int offset = y * width;
            for (int x = 0; x < width; x++)
            {
                pixels[offset + x] =
                    result.get(x, y) ? Color.BLACK : Color.TRANSPARENT;
            }
        }

        final Bitmap bitmap =
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

        return bitmap;
    }

    private static double parseNumberWorkaround(String numstr)
        throws NumberFormatException {
        // Some countries use comma as the decimal separator.
        // Android's numberDecimal EditText fields don't handle this
        // correctly (https://code.google.com/p/android/issues/detail?id=2626).
        // As a workaround we substitute ',' -> '.' manually ...
        return Double.parseDouble(numstr.toString().replace(',', '.'));
    }
}

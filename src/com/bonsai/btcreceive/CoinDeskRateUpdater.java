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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;

public class CoinDeskRateUpdater extends Thread implements RateUpdater {

    private static Logger mLogger =
        LoggerFactory.getLogger(CoinDeskRateUpdater.class);

    private double mRate = 0.0;
    private String mCode = "USD";
    private LocalBroadcastManager mLBM;

    private boolean mRunning = false;

    public CoinDeskRateUpdater(Context context, String code) {
        mLBM = LocalBroadcastManager.getInstance(context);
        if (code.equals("AUD") ||
            code.equals("CAD") ||
            code.equals("CHF") ||
            code.equals("CNY") ||
            code.equals("CZK") ||
            code.equals("DKK") ||
            code.equals("EUR") ||
            code.equals("GBP") ||
            code.equals("HUF") ||
            code.equals("ILS") ||
            code.equals("INR") ||
            code.equals("JPY") ||
            code.equals("LTL") ||
            code.equals("MXN") ||
            code.equals("NOK") ||
            code.equals("NZD") ||
            code.equals("PLN") ||
            code.equals("RUB") ||
            code.equals("SEK") ||
            code.equals("SGD") ||
            code.equals("THB") ||
            code.equals("TRY") ||
            code.equals("USD")) {
            mCode = code;
        }
    }

    public void startUpdater() {
        mRunning = true;
        this.start();
    }

    public void stopUpdater() {
        mRunning = false;
        // If we join here we block the GUI thread whilst the service
        // thread is in a pause sleep.  Just let the old updater hang
        // around until the thread finishes ...
    }

    @Override
    public void run() {
        mLogger.info("run loop starting");
        while (mRunning) {
            double rate = fetchLatestRate();

            // Did the rate change?
            if (mRate != rate) {
                mLogger.info(String.format("rate changed to %f", rate));
                mRate = rate;
                Intent intent = new Intent("rate-changed");
                mLBM.sendBroadcast(intent);
            }

            // Wait a while before doing it again.
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mLogger.info("run loop finished");
    }

    protected double fetchLatestRate() {
        try {
            DefaultHttpClient httpClient = new DefaultHttpClient();
            String url = "https://api.coindesk.com/v1/bpi/currentprice/" + mCode + ".json";
            HttpGet httpGet = new HttpGet(url);
 
            HttpResponse httpResponse = httpClient.execute(httpGet);
            HttpEntity httpEntity = httpResponse.getEntity();
            InputStream is = httpEntity.getContent();           
            BufferedReader reader =
                new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line = null;
            while ((line = reader.readLine()) != null) {
                sb.append(line + "\n");
            }
            is.close();
            String json = sb.toString();
            JSONObject jObj = new JSONObject(json);
            JSONObject bpiObj = jObj.getJSONObject("bpi");
            JSONObject usdObj = bpiObj.getJSONObject(mCode);
            double rate = usdObj.getDouble("rate_float");
            return rate;

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (ClientProtocolException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
		return 0;
    }

    public double getRate() {
        return mRate;
    }

    public String getCode() {
        return mCode;
    }
}

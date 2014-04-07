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

import android.app.ActionBar;
import android.app.ActionBar.Tab;
import android.app.ActionBar.TabListener;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;

public class MainActivity extends FragmentActivity {

    private static Logger mLogger =
        LoggerFactory.getLogger(MainActivity.class);

	private MyAdapter mAdapter;
	private ViewPager mPager;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		mAdapter = new MyAdapter(getSupportFragmentManager());

        final android.app.ActionBar actionBar = getActionBar();

		mPager = (ViewPager) findViewById(R.id.pager);
		mPager.setAdapter(mAdapter);

        // Specify that tabs should be displayed in the action bar.
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
                    mPager.setCurrentItem(tab.getPosition());
				}

				@Override
				public void onTabUnselected(Tab tab,
						android.app.FragmentTransaction ft) {
				}
            };

        // Add tabs, specifying the tab's text and TabListener
        for (int i = 0; i < 2; i++) {
            actionBar.addTab(
                             actionBar.newTab()
                             .setText("Tab " + (i + 1))
                             .setTabListener(tabListener));
        }
        
        mPager.setOnPageChangeListener
            (new ViewPager.SimpleOnPageChangeListener() {
                    @Override
                    public void onPageSelected(int position) {
                        // When swiping between pages, select the
                        // corresponding tab.
                        getActionBar().setSelectedNavigationItem(position);
                    }
                });

	}

	public static class MyAdapter extends FragmentPagerAdapter {
		public MyAdapter(FragmentManager fm) {
			super(fm);
		}

		@Override
		public int getCount() {
			return 2;
		}

		@Override
		public Fragment getItem(int position) {
			switch (position) {
			case 0:
				return new ReceiveFragment();
			case 1:
				return new TransactionsFragment();

			default:
				return null;
			}
		}
	}
}

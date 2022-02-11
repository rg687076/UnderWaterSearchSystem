package com.tks.uwsserverunit00.ui;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import android.graphics.Color;
import android.util.Pair;
import java.util.ArrayList;
import java.util.List;

import com.tks.uwsserverunit00.TLog;

public class FragMapViewModel extends ViewModel {
	/* Permission */
	private final MutableLiveData<Boolean>	mPermission = new MutableLiveData<>(false);
	public MutableLiveData<Boolean>			Permission() { return mPermission; }
	/* Selected Seeker */
	private final MutableLiveData<Pair<Short, Boolean>>	mSelectedSeeker = new MutableLiveData<>(Pair.create((short)-32768, false));
	private final List<Pair<Short, Boolean>>			mSelectedSeekerList = new ArrayList<>();
	public MutableLiveData<Pair<Short, Boolean>>		SelectedSeeker() { return mSelectedSeeker; }
	public void setSelected(short seekerid, boolean isChecked) {
		Pair<Short, Boolean> findit = mSelectedSeekerList.stream().filter(item->item.first==seekerid).findAny().orElse(null);
		if(findit==null) {
			/* 新規追加 */
			mSelectedSeekerList.add(Pair.create(seekerid, isChecked));
			mSelectedSeeker.setValue(Pair.create(seekerid, isChecked));
		}
		else {
			/* 値更新 */
			if(findit.second == isChecked)
				return;	/* 変更がないので何もしない */
			else {
				mSelectedSeekerList.remove(findit);
				mSelectedSeekerList.add(Pair.create(seekerid, isChecked));
				mSelectedSeeker.setValue(Pair.create(seekerid, isChecked));
			}
		}
	}


	/* Change Fill Color */
	private int mFillColorCnt = 0;
	/* 塗りつぶし色カウンタ */
	public int incrementFillColorCnt() {
		mFillColorCnt++;
		mFillColorCnt%=10;
		return mFillColorCnt;
	}

	/* 塗りつぶし色生成 */
	static private int createColor(int cnt) {
		switch(cnt) {
			case 0: return Color.rgb(  0,   0,   0);
			case 1: return Color.rgb(127,  79,  33);
			case 2: return Color.rgb(234,  44,  59);
			case 3: return Color.rgb(255, 106,  54);
			case 4: return Color.rgb(234, 190,  59);
			case 5: return Color.rgb(  0, 145,  58);
			case 6: return Color.rgb( 46, 167, 224);
			case 7: return Color.rgb(149,  85, 170);
			case 8: return Color.rgb(128, 128, 128);
			case 9: return Color.rgb(240, 240, 240);
			default:
				return Color.rgb(  0,   0,   0);
		}
	}

	/* 塗りつぶし色取得 */
	public int getFillColor() {
		return createColor(mFillColorCnt);
	}
}
// IBleClientService.aidl
package com.tks.uwsclient;
import com.tks.uwsclient.IBleClientServiceCallback;

interface IBleClientService {
	void setCallback(IBleClientServiceCallback callback);	/* 常に後勝ち */
	int initBle();
	void startAdvertising(int seekerid, float difflong, float difflat, int heartbeat);
	void stopAdvertising();
}
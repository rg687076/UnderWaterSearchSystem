// IBleClientServiceCallback.aidl
package com.tks.uwsclient;

interface IBleClientServiceCallback {
	/* アドバタイズ */
    void notifyAdvertising(int ret);
	double getLongitude();	/* 経度 */
	double getLatitude();	/* 緯度 */
	int getHeartbeat();		/* 脈拍 */
}
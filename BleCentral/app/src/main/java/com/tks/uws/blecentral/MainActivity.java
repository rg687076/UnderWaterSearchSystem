package com.tks.uws.blecentral;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanResult;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Rational;
import android.view.View;
import android.widget.Button;
import java.text.MessageFormat;
import java.util.List;

public class MainActivity extends AppCompatActivity {
	private DeviceListAdapter	mDeviceListAdapter;
	private final static int	REQUEST_ENABLE_BT	= 0x1111;
	private final static int	REQUEST_PERMISSIONS	= 0x2222;
	private Handler				mHandler = new Handler(Looper.getMainLooper());
	private final static long	SCAN_PERIOD = 30000;	/* m秒 */

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		/* BLEデバイスリストの初期化 */
		RecyclerView deviceListRvw = findViewById(R.id.rvw_devices);
		deviceListRvw.setHasFixedSize(true);
		deviceListRvw.setLayoutManager(new LinearLayoutManager(getApplicationContext()));
		mDeviceListAdapter = new DeviceListAdapter(new DeviceListAdapter.DeviceListAdapterListener() {
			@Override
			public void onDeviceItemClick(View view, String deviceName, String deviceAddress) {
				try { mBleServiceIf.stopScan(); }
				catch (RemoteException e) { e.printStackTrace(); }

				/* デバイス接続開始 */
				int ret = 0;
				try { ret = mBleServiceIf.connectDevice(deviceAddress); }
				catch (RemoteException e) { e.printStackTrace(); }
				switch(ret) {
					case Constants.UWS_NG_SUCCESS:
						TLog.d("デバイス接続中... {0}:{1}", deviceName, deviceAddress);
						mDeviceListAdapter.setStatus(deviceAddress, DeviceListAdapter.ConnectStatus.CONNECTING);
						break;
					case Constants.UWS_NG_RECONNECT_OK:
						TLog.d("デバイス再接続OK. {0}:{1}", deviceName, deviceAddress);
						mDeviceListAdapter.setStatus(deviceAddress, DeviceListAdapter.ConnectStatus.READY);
						break;
					case Constants.UWS_NG_DEVICE_NOTFOUND: {
							String logstr = MessageFormat.format("デバイスが見つかりません。デバイス:({0} : {1})", deviceName, deviceAddress);
							TLog.d(logstr);
							Snackbar.make(findViewById(R.id.root_view), logstr, Snackbar.LENGTH_LONG).show();
						}
						break;
					default: {
							String logstr = MessageFormat.format("デバイス接続::不明なエラー. デバイス:({0} : {1})", deviceName, deviceAddress);
							TLog.d(logstr);
							Snackbar.make(findViewById(R.id.root_view), logstr, Snackbar.LENGTH_LONG).show();
						}
						break;
				}
			}
		});
		deviceListRvw.setAdapter(mDeviceListAdapter);

		/* scanボタン押下 */
		findViewById(R.id.btnScan).setOnClickListener(view -> {
			startScan();
		});

		/* Bluetoothのサポート状況チェック 未サポート端末なら起動しない */
		if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			ErrPopUp.create(MainActivity.this).setErrMsg("Bluetoothが、未サポートの端末です。").Show(MainActivity.this);
		}

		/* 権限が許可されていない場合はリクエスト. */
		if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_PERMISSIONS);
		}

		final BluetoothManager bluetoothManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
		BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
		/* Bluetooth未サポート判定 未サポートならエラーpopupで終了 */
		if (bluetoothAdapter == null) {
			ErrPopUp.create(MainActivity.this).setErrMsg("Bluetoothが、未サポートの端末です。").Show(MainActivity.this);
		}
		/* Bluetooth ON/OFF判定 -> OFFならONにするようにリクエスト */
		else if( !bluetoothAdapter.isEnabled()) {
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
		}

		bindBleService();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if(requestCode == REQUEST_PERMISSIONS && grantResults.length > 0) {
			if (grantResults[0] != PackageManager.PERMISSION_GRANTED)
				ErrPopUp.create(MainActivity.this).setErrMsg("このアプリには必要な権限です。\n再起動後に許可してください。\n終了します。").Show(MainActivity.this);
			else
				bindBleService();
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if(requestCode == REQUEST_ENABLE_BT) {
			/* Bluetooth機能ONになった。 */
			if(resultCode == Activity.RESULT_OK)
				bindBleService();
			else
				ErrPopUp.create(MainActivity.this).setErrMsg("このアプリでは、Bluetooth機能をONにする必要があります。\n終了します。").Show(MainActivity.this);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		TLog.d("onDestroy()");
		unbindService(mCon);
	}

	private void bindBleService() {
		/* Bluetooth未サポート */
		if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			TLog.d("Bluetooth未サポートの端末.何もしない.");
			return;
		}

		/* 権限が許可されていない */
		if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			TLog.d("Bluetooth権限なし.何もしない.");
			return;
		}

		/* Bluetooth未サポート */
		final BluetoothManager bluetoothManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
		if(bluetoothManager == null) {
			TLog.d("Bluetooth未サポートの端末.何もしない.");
			return;
		}

		/* Bluetooth未サポート */
		BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
		if (bluetoothAdapter == null) {
			TLog.d("Bluetooth未サポートの端末.何もしない.");
			return;
		}

		/* Bluetooth ON/OFF判定 */
		if( !bluetoothAdapter.isEnabled()) {
			TLog.d("Bluetooth OFF.何もしない.");
			return;
		}

		/* Bluetoothサービス起動 */
		Intent intent = new Intent(MainActivity.this, BleService.class);
		bindService(intent, mCon, Context.BIND_AUTO_CREATE);
		TLog.d("Bluetooth使用クリア Bluetoothサービス起動");

		/* PinPで起動 */
		PictureInPictureParams.Builder pinpbuilder = new PictureInPictureParams.Builder();
		pinpbuilder.setAspectRatio(new Rational(16,9));
		enterPictureInPictureMode(pinpbuilder.build());
		TLog.d("PinP状態に遷移.");
	}

	/* Serviceコールバック */
	private IBleService mBleServiceIf;
	private final ServiceConnection mCon = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			mBleServiceIf = IBleService.Stub.asInterface(service);

//			/* コールバック設定 */
//			try { mBleServiceIf.setCallback(mCb); }
//			catch (RemoteException e) { e.printStackTrace(); throw new RuntimeException("AIDL-callback設定で失敗!!"); /* ここで例外が起きたら終了する */}

			/* BT初期化 */
			int ret = initBt();
			TLog.d("BT初期化完了 ret={0}", ret);

			/* 結果をUWSアプリに返却、自身(アプリ)は終了 */
//			Intent retintent = new Intent("com.tks.uws.blecentral.BT_INIT");
//			retintent.putExtra("RET_BT_INIT", ret);
//			setResult(Activity.RESULT_OK, retintent);
			finish();

//			/* scan開始 */
//			boolean retscan = startScan();
//			if(!retscan) return;
//			TLog.d("scan開始");
//
//			/* 30秒後にscan停止 */
//			mHandler.postDelayed(() -> {
//				stopScan();
//			}, SCAN_PERIOD);
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			TLog.d("onServiceDisconnected() name={0}", name);
			mBleServiceIf = null;
		}
	};

	/* AIDLコールバック */
	private IBleServiceCallback mCb = new IBleServiceCallback.Stub() {
		@Override
		public void notifyDeviceInfolist() throws RemoteException {
			List<DeviceInfo> result = mBleServiceIf.getDeviceInfolist();
			runOnUiThread(() -> mDeviceListAdapter.addDevice(result));
		}

		@Override
		public void notifyDeviceInfo() throws RemoteException {
			DeviceInfo result = mBleServiceIf.getDeviceInfo();
			runOnUiThread(() -> mDeviceListAdapter.addDevice(result));
			TLog.d("発見!! {0}({1}):Rssi({2})", result.getDeviceAddress() , result.getDeviceName(), result.getDeviceRssi());
		}

		@Override
		public void notifyScanEnd() throws RemoteException {
			TLog.d("scan終了");
			runOnUiThread(() -> {
				Button btn = findViewById(R.id.btnScan);
				btn.setText("scan開始");
				btn.setEnabled(true);
			});
		}

		@Override
		public void notifyGattConnected(String Address) throws RemoteException {
			/* Gatt接続完了 */
			TLog.d("Gatt接続OK!! -> Services探検中. Address={0}", Address);
			runOnUiThread(() -> { mDeviceListAdapter.setStatus(Address, DeviceListAdapter.ConnectStatus.EXPLORING); });
		}

		@Override
		public void notifyGattDisConnected(String Address) throws RemoteException {
			String logstr = MessageFormat.format("Gatt接続断!! Address={0}", Address);
			TLog.d(logstr);
			Snackbar.make(findViewById(R.id.root_view), logstr, Snackbar.LENGTH_LONG).show();
			runOnUiThread(() -> { mDeviceListAdapter.setStatus(Address, DeviceListAdapter.ConnectStatus.NONE); });
		}

		@Override
		public void notifyServicesDiscovered(String Address, int status) throws RemoteException {
			if(status == Constants.UWS_NG_GATT_SUCCESS) {
				TLog.d("Services発見. -> 対象Serviceかチェック ret={0}", status);
				runOnUiThread(() -> { mDeviceListAdapter.setStatus(Address, DeviceListAdapter.ConnectStatus.CHECKAPPLI); });
			}
			else {
				String logstr = MessageFormat.format("Services探索失敗!! 処理終了 ret={0}", status);
				TLog.d(logstr);
				runOnUiThread(() -> { mDeviceListAdapter.setStatus(Address, DeviceListAdapter.ConnectStatus.NONE); });
				Snackbar.make(findViewById(R.id.root_view), logstr, Snackbar.LENGTH_LONG).show();
			}
		}

		@Override
		public void notifyApplicable(String Address, boolean status) throws RemoteException {
			if(status) {
				TLog.d("対象Chk-OK. -> 通信準備中 Address={0}", Address);
				runOnUiThread(() -> { mDeviceListAdapter.setStatus(Address, DeviceListAdapter.ConnectStatus.TOBEPREPARED); });
			}
			else {
				String logstr = MessageFormat.format("対象外デバイス.　処理終了. Address={0}", Address);
				TLog.d(logstr);
				runOnUiThread(() -> { mDeviceListAdapter.setStatus(Address, DeviceListAdapter.ConnectStatus.NONE); });
				Snackbar.make(findViewById(R.id.root_view), logstr, Snackbar.LENGTH_LONG).show();
			}
		}

		@Override
		public void notifyReady2DeviceCommunication(String Address, boolean status) throws RemoteException {
			if(status) {
				String logstr = MessageFormat.format("BLEデバイス通信 準備完了. Address={0}", Address);
				TLog.d(logstr);
				runOnUiThread(() -> { mDeviceListAdapter.setStatus(Address, DeviceListAdapter.ConnectStatus.READY); });
				Snackbar.make(findViewById(R.id.root_view), logstr, Snackbar.LENGTH_LONG).show();
			}
			else {
				String logstr = MessageFormat.format("BLEデバイス通信 準備失敗!! Address={0}", Address);
				TLog.d(logstr);
				runOnUiThread(() -> { mDeviceListAdapter.setStatus(Address, DeviceListAdapter.ConnectStatus.NONE); });
				Snackbar.make(findViewById(R.id.root_view), logstr, Snackbar.LENGTH_LONG).show();
			}
		}

		@Override
		public void notifyResRead(String Address, int rcvval, int status) throws RemoteException {
			String logstr = MessageFormat.format("デバイス読込成功. Address={0} val={1} status={2}", Address, rcvval, status);
			TLog.d(logstr);
			runOnUiThread(() -> { mDeviceListAdapter.setHertBeat(Address, rcvval); });
			Snackbar.make(findViewById(R.id.root_view), logstr, Snackbar.LENGTH_LONG).show();
		}

		@Override
		public void notifyFromPeripheral(String Address, int rcvval) throws RemoteException {
			String logstr = MessageFormat.format("デバイス通知({0}). Address={1}", rcvval, Address);
			TLog.d(logstr);
			runOnUiThread(() -> { mDeviceListAdapter.setHertBeat(Address, rcvval); });
			Snackbar.make(findViewById(R.id.root_view), logstr, Snackbar.LENGTH_LONG).show();
		}

		@Override
		public void notifyError(int errcode, String errmsg) throws RemoteException {
			String logstr = MessageFormat.format("ERROR!! errcode={0} : {1}", errcode, errmsg);
			TLog.d(logstr);
			Snackbar.make(findViewById(R.id.root_view), logstr, Snackbar.LENGTH_LONG).show();
		}
	};

	/* Bluetooth初期化 */
	private int initBt() {
		TLog.d("コールバック設定完了");
		/* BT初期化 */
		int retini = 0;
		try { retini = mBleServiceIf.initBle(); }
		catch (RemoteException e) { e.printStackTrace(); throw new RuntimeException("Bt初期化で失敗!!"); /* ここで例外が起きたら終了する */}
		TLog.d("Bletooth初期化 ret={0}", retini);
		if(retini == Constants.UWS_NG_PERMISSION_DENIED)
			ErrPopUp.create(MainActivity.this).setErrMsg("このアプリに権限がありません!!\n終了します。").Show(MainActivity.this);
		else if(retini == Constants.UWS_NG_SERVICE_NOTFOUND)
			ErrPopUp.create(MainActivity.this).setErrMsg("この端末はBluetoothに対応していません!!\n終了します。").Show(MainActivity.this);
		else if(retini == Constants.UWS_NG_ADAPTER_NOTFOUND)
			ErrPopUp.create(MainActivity.this).setErrMsg("この端末はBluetoothに対応していません!!\n終了します。").Show(MainActivity.this);
		else if(retini == Constants.UWS_NG_BT_OFF) {
			Snackbar.make(findViewById(R.id.root_view), "BluetoothがOFFです。\nONにして操作してください。", Snackbar.LENGTH_LONG).show();
			return Constants.UWS_NG_BT_OFF;
		}
		else if(retini != Constants.UWS_NG_SUCCESS)
			ErrPopUp.create(MainActivity.this).setErrMsg("原因不明のエラーが発生しました!!\n終了します。").Show(MainActivity.this);

		return retini;
	}

	/* scan開始 */
	private boolean startScan() {
		int ret = 0;
		try { ret = mBleServiceIf.startScan();}
		catch (RemoteException e) { e.printStackTrace();}
		TLog.d("ret={0}", ret);
		if(ret == Constants.UWS_NG_ALREADY_SCANNED) {
			Snackbar.make(findViewById(R.id.root_view), "すでにscan中です。継続します。", Snackbar.LENGTH_LONG).show();
			return false;
		}
		else if(ret == Constants.UWS_NG_BT_OFF) {
			Snackbar.make(findViewById(R.id.root_view), "BluetoothがOFFです。\nONにして操作してください。", Snackbar.LENGTH_LONG).show();
			return false;
		}
		runOnUiThread(() -> { mDeviceListAdapter.clearDevice(); });
		try { mBleServiceIf.clearDevice();}
		catch (RemoteException e) { e.printStackTrace(); return false;}
		Button btn = findViewById(R.id.btnScan);
		btn.setText("scan中");
		btn.setEnabled(false);

		return true;
	}

	private boolean stopScan() {
		int ret = 0;
		try { ret = mBleServiceIf.stopScan();}
		catch (RemoteException e) { e.printStackTrace(); return false;}
		TLog.d("scan停止 ret={0}", ret);
		Button btn = findViewById(R.id.btnScan);
		btn.setText("scan開始");
		btn.setEnabled(true);
		return true;
	}
}

package com.tks.uwsserverunit00;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static com.tks.uwsserverunit00.Constants.BT_CLASSIC_UUID;
import static com.tks.uwsserverunit00.Constants.BT_NORTIFY_SEEKERID;
import static com.tks.uwsserverunit00.Constants.BT_NORTIFY_CLOSE;
import static com.tks.uwsserverunit00.Constants.d2Str;

/**
 * -30 dBm	素晴らしい	達成可能な最大信号強度。クライアントは、これを実現するには、APから僅か数フィートである必要があります。現実的には一般的ではなく、望ましいものでもありません	N/A
 * -60 dBm	良好	非常に信頼性の高く、データパケットのタイムリーな伝送を必要とするアプリケーションのための最小信号強度	VoIP/VoWiFi, ストリーミングビデオ
 * -70 dBm	Ok	信頼できるパケット伝送に必要な最小信号強度	Email, web
 * -80 dBm	よくない	基本的なコネクティビティに必要な最小信号強度。パケット伝送は信頼できない可能性があります	N/A
 * -90 dBm	使用不可	ノイズレベルに近いかそれ以下の信号強度。殆ど機能しない	N/A
 **/

public class UwsServerService extends Service {
	private IHearbertChangeListner	mHearbertGb;
	private ILocationChangeListner	mLocationGb;
	private IStatusNotifier			mStatusCb;
	private BluetoothAdapter		mBluetoothAdapter;
	private BtStandbyThread			mBtStandbyThread;
	private List<BtSndRcvThread>	mBtSndRcvThreads = new ArrayList<>();

	@Override
	public void onCreate() {
		super.onCreate();
		/* Bluetoothアダプタ取得 */
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if(mBluetoothAdapter == null) {
			stopSelf();
			throw new RuntimeException("ありえない。BluetoothAdapter is null!!");
		}

		/* Bluetooth無効 */
		if(!mBluetoothAdapter.isEnabled()) {
			stopSelf();
			throw new RuntimeException("ありえない。Bluetooth無効!!");
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		mBtSndRcvThreads.forEach(i -> {
			i.interrupt();
		});
		mBtSndRcvThreads.clear();
		mBtSndRcvThreads = null;
		mBtStandbyThread.interrupt();
		mBtStandbyThread = null;
		mBluetoothAdapter = null;
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		TLog.d("");
		return mBinder;
	}

	/** *****
	 * Binder
	 * ******/
	private final Binder mBinder = new IUwsServer.Stub() {
		@Override
		public void setListners(IHearbertChangeListner hGb, ILocationChangeListner lGb, IStatusNotifier sCb) {
			mHearbertGb	= hGb;
			mLocationGb	= lGb;
			mStatusCb	= sCb;
		}

		/* 起動条件チェッククリア */
		@Override
		public void notifyStartCheckCleared() {
			mBtStandbyThread = new BtStandbyThread();
			mBtStandbyThread.start();
		}
	};

	/* *********
	 * Bluetooth
	 * *********/
	public static final String BT_NAME = "BtServer";

	/* Acceput待ちスレッド */
	public class BtStandbyThread extends Thread {
		BluetoothServerSocket bluetoothServerSocket = null;

		@Override
		public void run() {
			/* 権限チェック、ホントはここでは不要だけど、Androidがうるさいから */
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				if (ActivityCompat.checkSelfPermission(UwsServerService.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
					throw new RuntimeException("すでに権限付与済のはず");
			}

			/* RFCOMM チャンネルの確立 */
			try {
				/* この関数はすぐ戻る */
				bluetoothServerSocket = mBluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(BT_NAME, BT_CLASSIC_UUID);
			}
			catch(IOException e) { TLog.d("ここでの失敗は想定外!!"); e.printStackTrace();}

			while(true) {
				/* スレッド停止チェック */
				if(Thread.interrupted()) {
					try { bluetoothServerSocket.close(); } catch(IOException ignore) { }
					break;
				}

				/* Client接続待ち */
				String name, addr;
				try {
					TLog.d("新Client待ち...");
					BluetoothSocket btSocket = bluetoothServerSocket.accept();
					name = btSocket.getRemoteDevice().getName();
					addr = btSocket.getRemoteDevice().getAddress();

					TLog.d("Client接続OK {0}:{1}", name, addr);
					try { mStatusCb.onChangeStatus(name, addr, R.string.status_connected); }
					catch(RemoteException ignore) {/* ここで例外が発生してもどうもしない */}

					try {
						BtSndRcvThread thred = new BtSndRcvThread(name, addr, btSocket);
						mBtSndRcvThreads.add(thred);
						thred.start();
					}
					catch(IOException e) {
						e.printStackTrace();
						try { mStatusCb.onChangeStatus(name, addr, R.string.err_btconnect_failured); }
						catch(RemoteException ignore) {/* ここで例外が発生するとどうしようもない */}
					}
				}
				catch(IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/* 送受信スレッド */
	public class BtSndRcvThread extends Thread {
		private String	name;
		private String	addr;
		private final BluetoothSocket	bluetoothSocket;
		private final InputStream		inputStream;
//		private final OutputStream		outputStream;
		public BtSndRcvThread(String aname, String aaddr, BluetoothSocket btSocket) throws IOException {
			TLog.d("受信スレッド起動 {0}:{1}", name, addr);
			name = aname;
			addr = aaddr;
			bluetoothSocket	= btSocket;
			inputStream		= bluetoothSocket.getInputStream();
//			outputStream	= bluetoothSocket.getOutputStream();
		}

		@Override
		public void run() {
			TLog.d("受信スレッド開始 {0}:{1}", name, addr);
			byte[] incomingLength = new byte[1]; /* 制限:最大256byteまで */
			byte[] incomingBuff = new byte[256];
			while(true) {
				/* スレッド停止チェック */
				if (Thread.interrupted()) {
					mBtSndRcvThreads.remove(this);
					TLog.d("受信 Tread終了 Thread数={0} {1}:{2}", mBtSndRcvThreads.size(), name, addr);
					try { inputStream    .close(); }catch(IOException ignore) { }
//					try { outputStream   .close(); }catch(IOException ignore) { }
					try { bluetoothSocket.close(); }catch(IOException ignore) { }
					break;
				}

				TLog.d("受信待ち... {0}:{1}", name, addr);
				try { mStatusCb.onChangeStatus(name, addr, R.string.status_waitforrecieve); }
				catch(RemoteException ignore) {/* ここで例外が発生してもどうもしない */}

				/* 1st-メッセージ長受信 */
				int incomingbodySize;
				try {
					inputStream.read(incomingLength);
					incomingbodySize = incomingLength[0];
				}
				catch(IOException e) {
					e.printStackTrace();
					try { mStatusCb.onChangeStatus(name, addr, R.string.err_btdisconnected); }
					catch(RemoteException ignore) {/* ここで例外が発生してもどうしようもない */}
					super.interrupt();
					continue;
				}

				TLog.d("受信!! {0}:{1}", name, addr);
				try { mStatusCb.onChangeStatus(name, addr, R.string.status_recieved); }
				catch(RemoteException ignore) {/* ここで例外が発生してもどうもしない */}

				/* 2nd-body受信 */
				byte[] completedata = new byte[incomingbodySize];
				int recieved = 0;
				try {
					while(recieved < completedata.length) {	/* recieved > rcvdata.lengthになることはない。 */
						int rcvSize = inputStream.read(incomingBuff, 0, incomingbodySize-recieved);
						System.arraycopy(incomingBuff, 0, completedata, recieved, rcvSize);
						recieved+=rcvSize;
					}
				}
				catch(IOException e) {
					e.printStackTrace();
					try { mStatusCb.onChangeStatus(name, addr, R.string.err_btdisconnected); }
					catch(RemoteException ignore) {/* ここで例外が発生してもどうしようもない */}
					super.interrupt();
					continue;
				}
				TLog.d("body受信 {0}:{1} rcv:({2},{3})", name, addr, completedata.length, Arrays.toString(completedata));
				char rcvtype = parseRcvAndCallback(name, addr, completedata);
				if(rcvtype == 'c') {
					/* Closeメッセージを受信したのでLoop終了する。 */
					TLog.d("CloseメッセージだったのでThred終了");
					this.interrupt();
				}

//				try {
//					outputStream.write((new Date().toString() + " OK").getBytes(StandardCharsets.UTF_8));}
//				catch(IOException e) {
//					e.printStackTrace();
//					try { mStatusCb.OnChangeStatus(name, addr, R.string.err_btdisconnected); }
//					catch(RemoteException ignore) {/* ここで例外が発生してもどうしようもない */}
//					super.interrupt();
//					continue;
//				}
			}
		}
	}

	/** **********
	 * データParse
	 ** **********/
	private char parseRcvAndCallback(String name, String addr, byte[] buff) {
		/* seekerid */
		short 	seekerid	= ByteBuffer.wrap(buff).getShort();
		/* 日付 */
		long 	ldatetime	= ByteBuffer.wrap(buff).getLong(2);
		/* データ種別 */
		char	datatype	= (char)buff[10];	/* '1':初回msg, 'h':脈拍, 'l':位置情報  */

		/* 初回受信 */
		if(datatype == '1') {
			/* seekerid */
			TLog.d("初回受信 {0} seekerid={1} {2}:{3}", d2Str(new Date(ldatetime)), seekerid, name, addr);
			/* 脈拍コールバック */
			try { mStatusCb.onChangeStatus(BT_NORTIFY_SEEKERID/*←例外的にSeekerid通知に使う*/, addr, seekerid);}
			catch(RemoteException e) { e.printStackTrace(); }
		}
		/* 脈拍受信 */
		else if(datatype == 'h') {
			/* 脈拍 */
			int	heartbeat	= ByteBuffer.wrap(buff).getInt(11);
			TLog.d("脈拍データ {0} {1} {2}:{3}", d2Str(new Date(ldatetime)), heartbeat, name, addr);
			/* 脈拍コールバック */
			try { mHearbertGb.onChange(seekerid, name, addr, ldatetime, heartbeat);}
			catch(RemoteException e) { e.printStackTrace(); }
		}
		/* 位置情報受信 */
		else if(datatype == 'l') {
			/* 位置情報 */
			double longitude= ByteBuffer.wrap(buff).getDouble(11);
			double latitude	= ByteBuffer.wrap(buff).getDouble(19);
			TLog.d("位置情報データ {0} ({1},{2}) {3}:{4}", d2Str(new Date(ldatetime)), longitude,latitude , name, addr);
			Location retloc =new Location(LocationManager.GPS_PROVIDER);
			retloc.setLongitude(longitude);
			retloc.setLatitude(latitude);
			/* 位置情報コールバック */
			try { mLocationGb.onChange(seekerid, name, addr, ldatetime, retloc); }
			catch(RemoteException e) { e.printStackTrace(); }
		}
		/* Close受信 */
		else if(datatype == 'c') {
			/* seekerid */
			TLog.d("Close受信 {0} seekerid={1} {2}:{3}", d2Str(new Date(ldatetime)), seekerid, name, addr);
			/* 脈拍コールバック */
			try { mStatusCb.onChangeStatus(BT_NORTIFY_CLOSE/*←例外的にClose通知に使う*/, addr, seekerid);}
			catch(RemoteException e) { e.printStackTrace(); }
		}

		/* 受信種別を返却 */
		return datatype;
	}
}

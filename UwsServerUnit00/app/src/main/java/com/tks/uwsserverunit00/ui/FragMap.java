package com.tks.uwsserverunit00.ui;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.location.Location;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.os.Looper;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;

import com.google.android.gms.maps.model.PolygonOptions;
import com.tks.uwsserverunit00.R;
import com.tks.uwsserverunit00.TLog;
import static com.tks.uwsserverunit00.Constants.UWS_LOC_BASE_DISTANCE_X;
import static com.tks.uwsserverunit00.Constants.UWS_LOC_BASE_DISTANCE_Y;
import static com.tks.uwsserverunit00.Constants.d2Str;

public class FragMap extends SupportMapFragment {
	private FragBleViewModel				mBleViewModel;
	private FragMapViewModel				mMapViewModel;
	private FragBizLogicViewModel			mBizLogicViewModel;
	private GoogleMap						mGoogleMap;
	private Location						mLocation;
	private final Map<String, MapDrawInfo>	mMapDrawInfos = new HashMap<>();
	/* ???????????? */
	static class MapDrawInfo {
		public Short	seekerid;
		public String	name;
		public String	address;
		public Date		date;
		public LatLng	pos;
		public Marker	maker;	/* GoogleMap??? Marker */
		public Polygon	polygon;/* GoogleMap??? Polygon */
		public Circle	circle;	/* GoogleMap??? Circle-?????????????????????????????? */
	};

	@NonNull
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return super.onCreateView(inflater, container, savedInstanceState);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		mBizLogicViewModel = new ViewModelProvider(requireActivity()).get(FragBizLogicViewModel.class);
		mBleViewModel = new ViewModelProvider(requireActivity()).get(FragBleViewModel.class);
		mMapViewModel = new ViewModelProvider(requireActivity()).get(FragMapViewModel.class);
		mMapViewModel.onCommanderPosChange().observe(getViewLifecycleOwner(), point -> {
			LatLng latlng = mGoogleMap.getProjection().fromScreenLocation(point);
			MapDrawInfo di = mMapDrawInfos.get("?????????");
			if(di != null) {
				if(di.maker!=null)	di.maker.remove();
				if(di.circle != null)	di.circle.remove();
				di.pos = latlng;
				di.maker = mGoogleMap.addMarker(new MarkerOptions().position(latlng).title("BasePos"));
				di.circle = mGoogleMap.addCircle(new CircleOptions()
						.center(latlng)
						.radius(0.5)
						.fillColor(Color.CYAN)
						.strokeColor(Color.CYAN));
			}
		});
		mMapViewModel.Permission().observe(getViewLifecycleOwner(), aBoolean -> {
			getNowPosAndDraw();
		});
		mMapViewModel.OnLocationUpdated().observe(getViewLifecycleOwner(), mapDrawInfo -> {
			updMapDrawInfo(mGoogleMap, mapDrawInfo);
		});
		mMapViewModel.onChangeStatus().observe(getViewLifecycleOwner(), mapDrawInfo -> {
			updMapDrawInfo(mGoogleMap, mapDrawInfo);
		});
		mMapViewModel.onTiltChange().observe(getViewLifecycleOwner(), tiltint -> {
			CameraPosition tilt = new CameraPosition.Builder(mGoogleMap.getCameraPosition()).tilt(tiltint).build();
			mGoogleMap.moveCamera(CameraUpdateFactory.newCameraPosition(tilt));
		});

		/* SupportMapFragment?????????????????????????????????????????????????????????????????????????????? */
		SupportMapFragment mapFragment = (SupportMapFragment) getActivity().getSupportFragmentManager().findFragmentById(R.id.frgMap);
		mapFragment.getMapAsync(new OnMapReadyCallback() {
			@Override
			public void onMapReady(@NonNull GoogleMap googleMap) {
				TLog.d("mLocation={0} googleMap={1}", mLocation, googleMap);
				if (mLocation == null) {
					/* ???????????????????????????????????????????????? */
					mLocation = new Location("");
					mLocation.setLongitude(130.20307019743947);
					mLocation.setLatitude(33.25923509336276);
				}
				mGoogleMap = googleMap;
				initDraw(mLocation, mGoogleMap);
			}
		});

		/* ??????????????? ??? ???????????? */
		getNowPosAndDraw();

		mMapViewModel.SelectedSeeker().observe(getViewLifecycleOwner(), new Observer<Pair<String, Boolean>>() {
			@Override
			public void onChanged(Pair<String, Boolean> pair) {
				String address		= pair.first;
				boolean	isSelected	= pair.second;

				MapDrawInfo si = mMapDrawInfos.get(address);
				if(si==null) return;

				if(isSelected) {
					if(si.pos == null) {
						TLog.d("si.pos(LatLng) is null.");
						getActivity().runOnUiThread(() -> mBleViewModel.onChangeStatus("", address, R.string.status_no_recieved_location));
						return;
					}
					if(si.maker!=null) {
						si.maker.remove();
						si.maker = null;
					}
					if(si.circle != null) {
						si.circle.remove();
						si.circle = null;
					}
					Marker marker = mGoogleMap.addMarker(new MarkerOptions()
							.position(si.pos)
							.title(String.valueOf(si.seekerid))
							.icon(createIcon(si.seekerid)));
					Circle nowPoint = mGoogleMap.addCircle(new CircleOptions().center(si.pos)
							.radius(0.5)
							.fillColor(Color.MAGENTA)
							.strokeColor(Color.MAGENTA));
					si.maker = marker;
					si.circle= nowPoint;
				}
				else {
					if(si.maker!=null) {
						si.maker.remove();
						si.maker = null;
					}
					if(si.circle != null) {
						si.circle.remove();
						si.circle = null;
					}
				}
			}
		});
	}

	/* ??????????????? ??? ???????????? */
	private void getNowPosAndDraw() {
		/* ???????????????????????????????????? */
		if(ActivityCompat.checkSelfPermission(getActivity().getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			return;
		}

		/* ???????????????????????????????????? */
		FusedLocationProviderClient flpc = LocationServices.getFusedLocationProviderClient(getActivity().getApplicationContext());
		flpc.getLastLocation().addOnSuccessListener(getActivity(), location -> {
			if (location == null) {
				TLog.d("mLocation={0} googleMap={1}", location, mGoogleMap);
				LocationRequest locreq = LocationRequest.create().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY).setInterval(500).setFastestInterval(300);
				flpc.requestLocationUpdates(locreq, new LocationCallback() {
					@Override
					public void onLocationResult(@NonNull LocationResult locationResult) {
						super.onLocationResult(locationResult);
						TLog.d("locationResult={0}({1},{2})", locationResult, locationResult.getLastLocation().getLatitude(), locationResult.getLastLocation().getLongitude());
						mLocation = locationResult.getLastLocation();
						flpc.removeLocationUpdates(this);
						initDraw(mLocation, mGoogleMap);
					}
				}, Looper.getMainLooper());
			}
			else {
				TLog.d("mLocation=(??????:{0} ??????:{1}) mMap={1}", location.getLatitude(), location.getLongitude(), mGoogleMap);
				mLocation = location;
				initDraw(mLocation, mGoogleMap);
			}
		});
	}

	/* ??????????????????(???????????????????????????????????????) */
	private void initDraw(Location location, GoogleMap googleMap) {
		if (location == null) return;
		if (googleMap == null) return;

		LatLng nowposgps = new LatLng(location.getLatitude(), location.getLongitude());
		TLog.d("??????:{0} ??????:{1}", d2Str(location.getLatitude()), d2Str(location.getLongitude()));
		TLog.d("?????? min:{0} max:{1}", googleMap.getMinZoomLevel(), googleMap.getMaxZoomLevel());

		MapDrawInfo di = mMapDrawInfos.get("?????????");
		if(di != null) {
			if(di.maker!=null)		di.maker.remove();
			if(di.circle != null)	di.circle.remove();
			mMapDrawInfos.remove("?????????");
		}
		/* ???????????????????????? */
		Marker basemarker = googleMap.addMarker(new MarkerOptions().position(nowposgps).title("BasePos"));
		Circle nowPoint = googleMap.addCircle(new CircleOptions()
												.center(nowposgps)
												.radius(0.5)
												.fillColor(Color.CYAN)
												.strokeColor(Color.CYAN));

		mMapDrawInfos.put("?????????", new MapDrawInfo(){{pos=nowposgps;maker=basemarker; circle=nowPoint; polygon=null;}});

		/* ?????????????????????????????? */
		googleMap.moveCamera(CameraUpdateFactory.newLatLng(nowposgps));
		TLog.d("CameraPosition:{0}", googleMap.getCameraPosition().toString());

		/* ????????????????????? */
		TLog.d("?????? zoom:{0}", 19);
		googleMap.moveCamera(CameraUpdateFactory.zoomTo(19));

		/* ???????????? 70?? */
		CameraPosition tilt = new CameraPosition.Builder(googleMap.getCameraPosition()).tilt(70).build();
		googleMap.moveCamera(CameraUpdateFactory.newCameraPosition(tilt));
	}

	private BitmapDescriptor createIcon(short seekerid) {
		switch(seekerid) {
			case 0: return BitmapDescriptorFactory.fromResource(R.drawable.marker0);
			case 1: return BitmapDescriptorFactory.fromResource(R.drawable.marker1);
			case 2: return BitmapDescriptorFactory.fromResource(R.drawable.marker2);
			case 3: return BitmapDescriptorFactory.fromResource(R.drawable.marker3);
			case 4: return BitmapDescriptorFactory.fromResource(R.drawable.marker4);
			case 5: return BitmapDescriptorFactory.fromResource(R.drawable.marker5);
			case 6: return BitmapDescriptorFactory.fromResource(R.drawable.marker6);
			case 7: return BitmapDescriptorFactory.fromResource(R.drawable.marker7);
			case 8: return BitmapDescriptorFactory.fromResource(R.drawable.marker8);
			case 9: return BitmapDescriptorFactory.fromResource(R.drawable.marker9);
			default:
				return BitmapDescriptorFactory.fromResource(R.drawable.marker3);
		}
	}

	/* Map??????????????? ?????? */
	private void updMapDrawInfo(GoogleMap googleMap, MapDrawInfo newmapInfo) {
		String	aaddress	= newmapInfo.address;
		LatLng	newpos		= (((int)newmapInfo.pos.latitude) == 0) ? null : newmapInfo.pos;
		short	oldseekerid	= mBleViewModel.getDeviceListAdapter().getSeekerId(aaddress);
		boolean	aIsSelected	= mBleViewModel.getDeviceListAdapter().isSelected(aaddress);/*?????????*/

		MapDrawInfo drawinfo = mMapDrawInfos.get(aaddress);
		if(drawinfo == null) {
			/* ???????????? */
			if(aIsSelected && newpos != null) {
				Marker lmarker = googleMap.addMarker(new MarkerOptions()
						.position(newpos)
						.title(String.valueOf(oldseekerid))
						.icon(createIcon(oldseekerid)));

				Circle nowPoint = googleMap.addCircle(new CircleOptions()
						.center(newpos)
						.radius(0.5)
						.fillColor(Color.MAGENTA)
						.strokeColor(Color.MAGENTA));
				TLog.d("Circle = {0}", nowPoint);

				MapDrawInfo mi = new MapDrawInfo(){{
					seekerid= newmapInfo.seekerid;
					name	= newmapInfo.name;
					address	= newmapInfo.address;
					date	= newmapInfo.date;
					pos		= newpos;
					maker	= lmarker;
					polygon = null;
					circle	= nowPoint;
				}};
				mMapDrawInfos.put(aaddress, mi);
				return;
			}
			else {
				MapDrawInfo mi = new MapDrawInfo(){{
					seekerid= newmapInfo.seekerid;
					name	= newmapInfo.name;
					address	= newmapInfo.address;
					date	= newmapInfo.date;
					pos		= newpos;
					maker	= null;
					polygon = null;
					circle	= null;
				}};
				mMapDrawInfos.put(aaddress, mi);
				return;
			}
		}
		else {
			/* ??????????????????pos?????????(???????????????????????????) */
			if(drawinfo.maker!=null) {
				drawinfo.maker.remove();
				drawinfo.maker = null;
			}
			if(drawinfo.circle!=null) {
				drawinfo.circle.remove();
				drawinfo.circle = null;
			}
//			if(drawinfo.polygon!=null) {	/* ????????????????????????????????? */
//				drawinfo.polygon.remove();
//				drawinfo.polygon = null;
//			}

			/* Cliant??????/??????????????????????????? */
			if(newpos == null) {
				drawinfo.seekerid= newmapInfo.seekerid;
				drawinfo.name	= newmapInfo.name;
				drawinfo.address= newmapInfo.address;
				drawinfo.date	= newmapInfo.date;
				drawinfo.pos	= null;
				drawinfo.maker	= null;
//				drawinfo.polygon= null;
				drawinfo.circle	= null;
				return;
			}

			/* ??????????????????????????? */
			if(drawinfo.pos == null) {
				drawinfo.pos = newpos;
				if(aIsSelected) {
					Marker marker = googleMap.addMarker(new MarkerOptions()
							.position(newpos)
							.title(String.valueOf(oldseekerid))
							.icon(createIcon(oldseekerid)));
					Circle nowPoint = googleMap.addCircle(new CircleOptions()
							.center(newpos)
							.radius(0.5)
							.fillColor(Color.MAGENTA)
							.strokeColor(Color.MAGENTA));
					drawinfo.maker = marker;
					drawinfo.circle= nowPoint;
				}
				else {
					drawinfo.maker = null;
					drawinfo.circle= null;
				}
				return;
			}

			/* ????????? */
			drawinfo.seekerid	= newmapInfo.seekerid;
			drawinfo.date		= newmapInfo.date;

			/* ???????????? */
			LatLng spos = drawinfo.pos;
			LatLng epos = newpos;
			drawinfo.pos = epos;
			double dx = epos.longitude- spos.longitude;
			double dy = epos.latitude - spos.latitude;
			if(Math.abs(dx) < 2*Float.MIN_VALUE && Math.abs(dy) < 2*Float.MIN_VALUE) {
				TLog.d("matrix ?????????????????????????????????????????? dx={0} dy={1} Double.MIN_VALUE={2}", dx, dy, Double.MIN_VALUE);
				return;
			}

			if(aIsSelected) {
				Marker marker = googleMap.addMarker(new MarkerOptions()
						.position(epos)
						.title(String.valueOf(oldseekerid))
						.icon(createIcon(oldseekerid)));
				Circle nowPoint = googleMap.addCircle(new CircleOptions()
						.center(epos)
						.radius(0.5)
						.fillColor(Color.MAGENTA)
						.strokeColor(Color.MAGENTA));
				drawinfo.maker = marker;
				drawinfo.circle= nowPoint;

				/* ???????????????????????????????????? */
				LatLng[] square = createSquare(spos, epos, UWS_LOC_BASE_DISTANCE_X, UWS_LOC_BASE_DISTANCE_Y);

				if(mBizLogicViewModel.getSerchStatus()/*?????????*/) {
					/* ???????????? */
					Polygon polygon = googleMap.addPolygon(new PolygonOptions()
							.fillColor(mMapViewModel.getFillColor())
							.strokeColor(Color.BLUE)
							.strokeWidth(1)
							.add(square[0], square[1], square[3], square[2]));
					drawinfo.polygon = polygon;
				}
			}
		}
	}

	private LatLng[] createSquare(final LatLng spos, final LatLng epos, final double BASE_DISTANCE_X, final double BASE_DISTANCE_Y) {
		/* 0-1.????????? ?????????????????????(degrees)???????????? */
		double dx = epos.longitude* (BASE_DISTANCE_X/100000) - spos.longitude* (BASE_DISTANCE_X/100000);
		double dy = epos.latitude * (BASE_DISTANCE_Y/100000) - spos.latitude * (BASE_DISTANCE_Y/100000);
		double degrees = Math.atan2(dy, dx) * 180 / Math.PI;

		/* 0-2.????????? ???????????? */
		Matrix mat = new Matrix();
		mat.reset();

		/* 1.??????????????????(cm????????? ??? ??????????????????) */
		mat.postScale((float)(1/(BASE_DISTANCE_X*100)), (float)(1/(BASE_DISTANCE_Y*100)));

		/* 2.????????????(?????????????????????) */
		mat.postRotate((float)degrees);

		/* 3.???????????????????????????offset???????????? */
		float[] src4vertex = {/*??????*/50,50,/*??????*/50,-50,/*??????*/-50,50,/*??????*/-50,-50};
		float[] dst4vertex = new float[src4vertex.length];
		mat.mapPoints(dst4vertex, src4vertex);

		/* 4.?????????/????????????offset????????????????????? */
		LatLng ltpos = new LatLng(epos.latitude+dst4vertex[1], epos.longitude+dst4vertex[0]);
		LatLng rtpos = new LatLng(epos.latitude+dst4vertex[3], epos.longitude+dst4vertex[2]);
		LatLng lbpos = new LatLng(spos.latitude+dst4vertex[5], spos.longitude+dst4vertex[4]);
		LatLng rbpos = new LatLng(spos.latitude+dst4vertex[7], spos.longitude+dst4vertex[6]);
		return new LatLng[]{ltpos, rtpos, lbpos, rbpos};
	}
}
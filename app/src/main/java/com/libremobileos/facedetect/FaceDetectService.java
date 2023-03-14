package com.libremobileos.facedetect;

import static android.app.job.JobInfo.PRIORITY_MIN;
import static android.os.Process.THREAD_PRIORITY_FOREGROUND;
import static com.libremobileos.facedetect.BuildConfig.DEBUG;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.hardware.biometrics.face.V1_0.FaceAcquiredInfo;
import android.hardware.biometrics.face.V1_0.Feature;
import android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback;
import android.hardware.biometrics.face.V1_0.OptionalBool;
import android.hardware.biometrics.face.V1_0.OptionalUint64;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.HwBinder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.libremobileos.yifan.face.DirectoryFaceStorageBackend;
import com.libremobileos.yifan.face.FaceDataEncoder;
import com.libremobileos.yifan.face.FaceRecognizer;
import com.libremobileos.yifan.face.FaceStorageBackend;
import com.libremobileos.yifan.face.ImageUtils;
import com.libremobileos.yifan.face.SharedPreferencesFaceStorageBackend;

import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback;
import android.hardware.biometrics.face.V1_0.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class FaceDetectService extends Service {
	private String TAG = "FaceUnlockService";
	private long kDeviceId = 123; // Arbitrary value.
	private long kAuthenticatorId = 987; // Arbitrary value.
	private int kFaceId = 100; // Arbitrary value.

	private static final int MSG_CHALLENGE_TIMEOUT = 100;

	private IBiometricsFaceClientCallback mCallback;
	private FaceRecognizer faceRecognizer;
	private FaceHandler mWorkHandler;
	private Context mContext;
	private long mChallenge = 0;
	private int mChallengeCount = 0;
	private boolean computingDetection = false;
	private CameraService mCameraService;
	private int mUserId = 0;

	private class BiometricsFace extends IBiometricsFace.Stub {
		@Override
		public OptionalUint64 setCallback(IBiometricsFaceClientCallback clientCallback) throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "setCallback");

			mCallback = clientCallback;
			OptionalUint64 ret = new OptionalUint64();
			ret.value = kDeviceId;
			ret.status = Status.OK;
			return ret;
		}

		@Override
		public int setActiveUser(int userId, String storePath) throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "setActiveUser " + userId + " " + storePath);

			return Status.OK;
		}

		@Override
		public OptionalUint64 generateChallenge(int challengeTimeoutSec) throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "generateChallenge + " + challengeTimeoutSec);

			if (mChallengeCount <= 0 || mChallenge == 0) {
				mChallenge = new Random().nextLong();
			}
			mChallengeCount += 1;
			mWorkHandler.removeMessages(MSG_CHALLENGE_TIMEOUT);
			mWorkHandler.sendEmptyMessageDelayed(MSG_CHALLENGE_TIMEOUT, challengeTimeoutSec * 1000);

			OptionalUint64 ret = new OptionalUint64();
			ret.value = mChallenge;
			ret.status = Status.OK;

			return ret;
		}

		@Override
		public int enroll(ArrayList<Byte> hat, int timeoutSec, ArrayList<Integer> disabledFeatures) throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "enroll");

			return Status.OK;
		}

		@Override
		public int revokeChallenge() throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "revokeChallenge");

			mChallengeCount -= 1;
			if (mChallengeCount <= 0 && mChallenge != 0) {
				mChallenge = 0;
				mChallengeCount = 0;
				mWorkHandler.removeMessages(MSG_CHALLENGE_TIMEOUT);
			}
			return Status.OK;
		}

		@Override
		public int setFeature(int feature, boolean enabled, ArrayList<Byte> hat, int faceId) throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "setFeature " + feature + " " + enabled + " " + faceId);

			// We don't do that here;

			return Status.OK;
		}

		@Override
		public OptionalBool getFeature(int feature, int faceId) throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "getFeature " + feature + " " + faceId);

			OptionalBool ret = new OptionalBool();
			switch (feature) {
				case Feature.REQUIRE_ATTENTION:
					ret.value = false;
					ret.status = Status.OK;
					break;
				case Feature.REQUIRE_DIVERSITY:
					ret.value = true;
					ret.status = Status.OK;
					break;
				default:
					ret.value = false;
					ret.status = Status.ILLEGAL_ARGUMENT;
					break;
			}
			return ret;
		}

		@Override
		public OptionalUint64 getAuthenticatorId() throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "getAuthenticatorId");

			OptionalUint64 ret = new OptionalUint64();
			ret.value = kAuthenticatorId;
			ret.status = Status.OK;
			return ret;
		}

		@Override
		public int cancel() throws RemoteException {
			// Not sure what to do here.
			mCameraService.closeCamera();
			mCameraService.stopBackgroundThread();
			return 0;
		}

		@Override
		public int enumerate() throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "enumerate");

			mWorkHandler.post(() -> {
				ArrayList<Integer> faceIds = new ArrayList<>();
				RemoteFaceServiceClient.connect(mContext, faced -> {
					if (faced.isEnrolled()) {
						faceIds.add(kFaceId);
						Log.d(TAG, "enumerate face added");
					}
					if (mCallback != null) {
						try {
							mCallback.onEnumerate(kDeviceId, faceIds, mUserId);
						} catch (RemoteException e) {
							e.printStackTrace();
						}
					}
				});
			});

			return Status.OK;
		}

		@Override
		public int remove(int faceId) throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "remove " + faceId);

			mWorkHandler.post(() -> {
				RemoteFaceServiceClient.connect(mContext, faced -> {
					if ((faceId == kFaceId || faceId == 0) && faced.isEnrolled()) {
						faced.unenroll();
						ArrayList<Integer> faceIds = new ArrayList<>();
						faceIds.add(faceId);
						try {
							if (mCallback != null)
								mCallback.onRemoved(kDeviceId, faceIds, mUserId);
						} catch (RemoteException e) {
							e.printStackTrace();
						}
					}
				});
			});
			return Status.OK;
		}

		@Override
		public int authenticate(long operationId) throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "authenticate " + operationId);

			mCameraService = new CameraService(mContext, faceCallback);
			mWorkHandler.post(() -> {
				mCameraService.startBackgroundThread();
				mCameraService.openCamera();
			});
			return Status.OK;
		}

		@Override
		public int userActivity() throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "userActivity");

			return Status.OK;
		}

		@Override
		public int resetLockout(ArrayList<Byte> hat) throws RemoteException {
			if (DEBUG)
				Log.d(TAG, "resetLockout");

			return Status.OK;
		}
	}

	CameraService.CameraCallback faceCallback = new CameraService.CameraCallback() {
		@Override
		public void setupFaceRecognizer ( final Size bitmapSize, int rotation) {
			// Store registered Faces
			// example for in-memory: FaceStorageBackend faceStorage = new VolatileFaceStorageBackend();
			// example for shared preferences: FaceStorageBackend faceStorage = new SharedPreferencesFaceStorageBackend(getSharedPreferences("faces", 0));
			FaceStorageBackend faceStorage = new DirectoryFaceStorageBackend(getFilesDir());

			// Create AI-based face detection
			faceRecognizer = FaceRecognizer.create(mContext,
					faceStorage, /* face data storage */
					0.6f, /* minimum confidence to consider object as face */
					bitmapSize.getWidth(), /* bitmap width */
					bitmapSize.getHeight(), /* bitmap height */
					rotation,
					0.7f, /* maximum distance (to saved face model, not from camera) to track face */
					1 /* minimum model count to track face */
			);
		}

		@Override
		public void processImage (Size previewSize, Size rotatedSize, Bitmap rgbBitmap,int rotation)
		{
			// No mutex needed as this method is not reentrant.
			if (computingDetection) {
				mCameraService.readyForNextImage();
				return;
			}
			computingDetection = true;
			List<FaceRecognizer.Face> data = faceRecognizer.recognize(rgbBitmap);
			computingDetection = false;

			ArrayList<Pair<RectF, String>> bounds = new ArrayList<>();
			// Camera is frontal so the image is flipped horizontally,
			// so flip it again (and rotate Rect to match preview rotation)
			Matrix flip = ImageUtils.getTransformationMatrix(previewSize.getWidth(), previewSize.getHeight(), rotatedSize.getWidth(), rotatedSize.getHeight(), rotation, false);
			flip.preScale(1, -1, previewSize.getWidth() / 2f, previewSize.getHeight() / 2f);

			for (FaceRecognizer.Face face : data) {
				RectF boundingBox = new RectF(face.getLocation());
				flip.mapRect(boundingBox);

				try {
					if (mCallback != null) {
						mCallback.onAcquired(kDeviceId, mUserId, FaceAcquiredInfo.GOOD, 0);
						// Do we have any match?
						if (face.isRecognized()) {
							ArrayList<Byte> hat = new ArrayList<>();
							for (byte b : face.getHat()) {
								hat.add(b);
							}
							mCallback.onAuthenticated(kDeviceId, kFaceId, mUserId, hat);
							mCameraService.closeCamera();
							mCameraService.stopBackgroundThread();
						}
					}
				} catch (RemoteException e) {
					e.printStackTrace();
				}
			}

			mCameraService.readyForNextImage();
		}
	};

	private class FaceHandler extends Handler {
		public FaceHandler(Looper looper) {
			super(looper);
		}

		@Override
		public void handleMessage(Message message) {
			if (message.what == MSG_CHALLENGE_TIMEOUT) {
				mChallenge = 0;
				mChallengeCount = 0;
			}
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);

		return START_STICKY;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		mContext = this;
		mUserId = 0; // TODO: Get real user id

		HandlerThread handlerThread = new HandlerThread(TAG, THREAD_PRIORITY_FOREGROUND);
		handlerThread.start();
		mWorkHandler = new FaceHandler(handlerThread.getLooper());

		BiometricsFace biometricsFace = new BiometricsFace();
		try {
			biometricsFace.registerAsService("default");
		} catch (RemoteException e) {
			e.printStackTrace();
		}

		// Create the Foreground Service
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		String channelId = createNotificationChannel(notificationManager);
		NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId);
		Notification notification = notificationBuilder.setOngoing(true)
				.setSmallIcon(R.mipmap.ic_launcher)
				.setPriority(PRIORITY_MIN)
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.build();

		startForeground(101, notification);
	}

	private final IFaceDetectService.Stub binder = new IFaceDetectService.Stub() {
		@Override
		public void enrollResult(int remaining) throws RemoteException {
			if (mCallback != null) {
				mCallback.onEnrollResult(kDeviceId, kFaceId, mUserId, remaining);
			}
		}

		@Override
		public void error(int error) throws RemoteException {
			if (mCallback != null) {
				mCallback.onError(kDeviceId, mUserId, error, 0);
			}
		}
	};

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private String createNotificationChannel(NotificationManager notificationManager){
		String channelId = "my_service_channelid";
		String channelName = "My Foreground Service";
		NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
		// omitted the LED color
		channel.setImportance(NotificationManager.IMPORTANCE_NONE);
		channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
		notificationManager.createNotificationChannel(channel);
		return channelId;
	}
}
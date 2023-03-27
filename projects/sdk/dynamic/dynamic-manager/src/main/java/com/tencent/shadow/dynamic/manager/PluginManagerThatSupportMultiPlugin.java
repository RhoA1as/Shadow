package com.tencent.shadow.dynamic.manager;

import static android.content.Context.BIND_AUTO_CREATE;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import com.tencent.shadow.core.common.Logger;
import com.tencent.shadow.core.common.LoggerFactory;
import com.tencent.shadow.dynamic.host.FailedException;
import com.tencent.shadow.dynamic.host.PluginManagerImpl;
import com.tencent.shadow.dynamic.host.PluginProcessService;
import com.tencent.shadow.dynamic.host.PpsController;
import com.tencent.shadow.dynamic.host.PpsStatus;
import com.tencent.shadow.dynamic.loader.PluginLoader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MIUI ADD
 * Manager support multiPlugin
 */
public abstract class PluginManagerThatSupportMultiPlugin extends BaseDynamicPluginManager implements PluginManagerImpl {

    private static final Logger mLogger = LoggerFactory.getLogger(PluginManagerThatSupportMultiPlugin.class);

    /**
     * 插件进程PluginProcessService的接口
     * key: plugin partKey
     */
    protected Map<String, PpsController> mPpsControllerMap = new ConcurrentHashMap<>();

    /**
     * 插件加载服务端接口
     * key: plugin partKey
     */
    protected Map<String, PluginLoader> mPluginLoaderMap = new ConcurrentHashMap<>();

    /**
     * 防止绑定service重入
     */
    protected Map<String, Boolean> mServiceConnectingMap = new ConcurrentHashMap<>();

    /**
     * 等待service绑定完成的计数器
     */
    protected Map<String, CountDownLatch> mConnectCountDownLatchMap = new ConcurrentHashMap<>();

    public PluginManagerThatSupportMultiPlugin(Context context) {
        super(context);
    }


    @Override
    protected void onPluginServiceConnected(ComponentName name, IBinder service) {
        //do nothing
    }

    @Override
    protected void onPluginServiceDisconnected(ComponentName name) {
        //do nothing
    }


    @SuppressLint("NewApi")
    public void bindPluginProcessService(final String serviceName, final String partKey) {
        if (mServiceConnectingMap.getOrDefault(partKey, false)) {
            if (mLogger.isInfoEnabled()) {
                mLogger.info("pps service connecting");
            }
            return;
        }
        if (mLogger.isInfoEnabled()) {
            mLogger.info("bindPluginProcessService " + serviceName);
        }
        synchronized (this) {
            if (mServiceConnectingMap.getOrDefault(partKey, false)) {
                if (mLogger.isInfoEnabled()) {
                    mLogger.info("pps service connecting");
                }
                return;
            }
            mConnectCountDownLatchMap.put(partKey, new CountDownLatch(1));
            mServiceConnectingMap.put(partKey, true);
        }

        final CountDownLatch startBindingLatch = new CountDownLatch(1);
        final boolean[] asyncResult = new boolean[1];
        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(mHostContext, serviceName));
                final CountDownLatch countDownLatch = mConnectCountDownLatchMap.get(partKey);
                boolean binding = mHostContext.bindService(intent, new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        if (mLogger.isInfoEnabled()) {
                            mLogger.info("onServiceConnected connectCountDownLatch:" + countDownLatch);
                        }
                        mServiceConnectingMap.put(partKey, false);

                        // service connect 后处理逻辑
                        onPluginServiceConnected(name, service, partKey);

                        countDownLatch.countDown();

                        if (mLogger.isInfoEnabled()) {
                            mLogger.info("onServiceConnected countDown:" + countDownLatch);
                        }
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        if (mLogger.isInfoEnabled()) {
                            mLogger.info("onServiceDisconnected");
                        }
                        mServiceConnectingMap.put(partKey, false);
                        onPluginServiceDisconnected(name, partKey);
                    }
                }, BIND_AUTO_CREATE);
                asyncResult[0] = binding;
                startBindingLatch.countDown();
            }
        });
        try {
            //等待bindService真正开始
            startBindingLatch.await(10, TimeUnit.SECONDS);
            if (!asyncResult[0]) {
                throw new IllegalArgumentException("无法绑定PPS:" + serviceName);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void waitServiceConnected(int timeout, TimeUnit timeUnit, String partKey) throws TimeoutException {
        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            throw new RuntimeException("waitServiceConnected 不能在主线程中调用");
        }
        try {
            final CountDownLatch countDownLatch = mConnectCountDownLatchMap.get(partKey);
            if (mLogger.isInfoEnabled()) {
                mLogger.info("waiting service connect connectCountDownLatch:" + countDownLatch);
            }
            long s = System.currentTimeMillis();
            boolean isTimeout = !countDownLatch.await(timeout, timeUnit);
            if (isTimeout) {
                throw new TimeoutException("连接Service超时 ,等待了：" + (System.currentTimeMillis() - s));
            }
            if (mLogger.isInfoEnabled()) {
                mLogger.info("service connected " + (System.currentTimeMillis() - s));
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    protected void onPluginServiceConnected(ComponentName name, IBinder service, String partKey) {
        PpsController ppsController = PluginProcessService.wrapBinder(service);
        mPpsControllerMap.put(partKey, ppsController);
        try {
            ppsController.setUuidManager(new UuidManagerBinder(PluginManagerThatSupportMultiPlugin.this));
        } catch (DeadObjectException e) {
            if (mLogger.isErrorEnabled()) {
                mLogger.error("onServiceConnected RemoteException:" + e);
            }
        } catch (RemoteException e) {
            if (e.getClass().getSimpleName().equals("TransactionTooLargeException")) {
                if (mLogger.isErrorEnabled()) {
                    mLogger.error("onServiceConnected TransactionTooLargeException:" + e);
                }
            } else {
                throw new RuntimeException(e);
            }
        }

        try {
            IBinder iBinder = ppsController.getPluginLoader();
            if (iBinder != null) {
                PluginLoader pluginLoader = new BinderPluginLoader(iBinder);
                mPluginLoaderMap.put(partKey, pluginLoader);
            }
        } catch (RemoteException ignored) {
            if (mLogger.isErrorEnabled()) {
                mLogger.error("onServiceConnected mPpsController getPluginLoader:", ignored);
            }
        }
    }

    protected void onPluginServiceDisconnected(ComponentName name, String partKey) {
        mPpsControllerMap.remove(partKey);
        mPluginLoaderMap.remove(partKey);
    }



    public final void loadRunTime(String uuid, String partKey) throws RemoteException, FailedException {
        PpsController ppsController = mPpsControllerMap.get(partKey);
        if (mLogger.isInfoEnabled()) {
            mLogger.info("loadRunTime mPpsController:" + ppsController);
        }
        PpsStatus ppsStatus = ppsController.getPpsStatus();
        if (!ppsStatus.runtimeLoaded) {
            ppsController.loadRuntime(uuid);
        }
    }

    public final void loadPluginLoader(String uuid, String partKey) throws RemoteException, FailedException {
        PluginLoader pluginLoader = mPluginLoaderMap.get(partKey);
        PpsController ppsController = mPpsControllerMap.get(partKey);
        if (mLogger.isInfoEnabled()) {
            mLogger.info("loadPluginLoader mPluginLoader:" + pluginLoader);
        }
        if (pluginLoader == null) {
            PpsStatus ppsStatus = ppsController.getPpsStatus();
            if (!ppsStatus.loaderLoaded) {
                ppsController.loadPluginLoader(uuid);
            }
            IBinder iBinder = ppsController.getPluginLoader();
            pluginLoader = new BinderPluginLoader(iBinder);
            mPluginLoaderMap.put(partKey, pluginLoader);
        }
    }
}

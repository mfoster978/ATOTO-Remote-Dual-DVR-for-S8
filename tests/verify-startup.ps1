$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Work = Join-Path $ProjectRoot '.private\startup-tests'
$Classes = Join-Path $Work 'classes'
$JavaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } elseif (Test-Path 'C:\Program Files\Android\Android Studio\jbr') { 'C:\Program Files\Android\Android Studio\jbr' } else { 'C:\Program Files\Android\Android Studio1\jbr' }
$Utf8 = New-Object System.Text.UTF8Encoding($false)
New-Item -ItemType Directory -Force -Path $Classes | Out-Null

# Compile the actual recovery and viewer sources against a small deterministic
# Android runtime. No radio, ADB, network, parking controls, or external dependencies.
$Stubs = @{
    'android/os/SystemClock.java' = 'package android.os; public class SystemClock { public static long now=1000000; public static long elapsedRealtime(){return now;} }'
    'android/os/Looper.java' = 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }'
    'android/os/Build.java' = 'package android.os; public class Build { public static class VERSION { public static int SDK_INT=33; } }'
    'android/os/Handler.java' = @'
package android.os;
import java.util.*;
public class Handler {
    static class Task { Runnable r; long at; Task(Runnable r,long at){this.r=r;this.at=at;} }
    static List<Task> tasks=new ArrayList<>();
    public Handler(Looper looper){}
    public boolean postDelayed(Runnable r,long delay){tasks.add(new Task(r,SystemClock.now+delay));return true;}
    public void removeCallbacks(Runnable r){tasks.removeIf(t->t.r==r);}
    public static int pending(){return tasks.size();}
    public static void reset(){tasks.clear();}
    public static void advance(long ms){long end=SystemClock.now+ms;while(true){Task next=null;for(Task t:tasks)if(t.at<=end&&(next==null||t.at<next.at))next=t;if(next==null)break;tasks.remove(next);SystemClock.now=next.at;next.r.run();}SystemClock.now=end;}
}
'@
    'android/util/Log.java' = 'package android.util; public class Log { public static int i(String t,String m){return 0;} public static int w(String t,String m,Throwable e){return 0;} public static int w(String t,String m){return 0;} }'
    'android/util/Base64.java' = 'package android.util; public class Base64 { public static final int NO_WRAP=2; public static String encodeToString(byte[] b,int f){return java.util.Base64.getEncoder().encodeToString(b);} }'
    'android/util/AttributeSet.java' = 'package android.util; public interface AttributeSet {}'
    'android/content/Intent.java' = @'
package android.content;
import java.util.*;
public class Intent {
    public static final String ACTION_BOOT_COMPLETED="boot",ACTION_LOCKED_BOOT_COMPLETED="lockedboot";
    public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_NO_ANIMATION=2,FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS=4;
    public String action,className; public Map<String,Boolean> extras=new HashMap<>();
    public Intent(){} public Intent(String s){action=s;} public Intent(Context c,Class<?> cls){}
    public Intent setClassName(String p,String c){className=c;return this;} public Intent putExtra(String s,String v){return this;}
    public String getAction(){return action;} public Intent setAction(String s){action=s;return this;}
    public Intent addFlags(int f){return this;} public Intent setPackage(String p){return this;}
    public Intent putExtra(String s,boolean b){extras.put(s,b);return this;}
    public boolean hasExtra(String s){return extras.containsKey(s);} public boolean getBooleanExtra(String s,boolean d){return extras.getOrDefault(s,d);}
}
'@
    'android/content/BroadcastReceiver.java' = 'package android.content; public abstract class BroadcastReceiver { public abstract void onReceive(Context c,Intent i); }'
    'android/content/SharedPreferences.java' = @'
package android.content;
import java.util.*;
public class SharedPreferences {
    Map<String,String> values=new HashMap<>();
    public String getString(String s,String d){return values.getOrDefault(s,d);} public Editor edit(){return new Editor();}
    public class Editor { public Editor putString(String s,String v){values.put(s,v);return this;} public Editor putLong(String s,long v){return this;} public void apply(){} }
}
'@
    'android/content/Context.java' = @'
package android.content;
import android.app.AlarmManager;
import com.codex.hn7camerastream.CameraStreamService;
public class Context {
    public static final int MODE_PRIVATE=0; public static final String ALARM_SERVICE="alarm";
    public int starts,stops,vpn,failStarts,factoryStarts; public AlarmManager alarms=new AlarmManager(); public SharedPreferences prefs=new SharedPreferences();
    public Context getApplicationContext(){return this;} public SharedPreferences getSharedPreferences(String s,int m){return prefs;}
    public String getPackageName(){return "com.hcn.dvr";}
    public Object getSystemService(String s){return alarms;}
    public Object startForegroundService(Intent i){if("com.hcn.dvr.service.VideoService".equals(i.className)){factoryStarts++;return null;}starts++;if(failStarts>0){failStarts--;throw new IllegalStateException("not ready");}CameraStreamService.instance=new CameraStreamService();return null;}
    public boolean stopService(Intent i){stops++;CameraStreamService.instance=null;return true;} public void sendBroadcast(Intent i){vpn++;}
}
'@
    'android/app/PendingIntent.java' = @'
package android.app;
import android.content.*;
public class PendingIntent {
    public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;
    public final int id; public PendingIntent(int id){this.id=id;}
    public static PendingIntent getActivity(Context c,int id,Intent i,int f){return new PendingIntent(id);}
    public static PendingIntent getBroadcast(Context c,int id,Intent i,int f){return new PendingIntent(id);}
    public void cancel(){} public int hashCode(){return id;} public boolean equals(Object o){return o instanceof PendingIntent&&((PendingIntent)o).id==id;}
}
'@
    'android/app/AlarmManager.java' = @'
package android.app;
import java.util.*;
public class AlarmManager {
    public static final int ELAPSED_REALTIME=3;
    public Map<PendingIntent,Long> alarms=new HashMap<>(); public boolean denyCancel;
    public void setExactAndAllowWhileIdle(int type,long at,PendingIntent p){if(type!=ELAPSED_REALTIME)throw new AssertionError("waking alarm");alarms.put(p,at);}
    public void setExact(int type,long at,PendingIntent p){setExactAndAllowWhileIdle(type,at,p);}
    public void setAndAllowWhileIdle(int type,long at,PendingIntent p){setExactAndAllowWhileIdle(type,at,p);}
    public void set(int type,long at,PendingIntent p){setExactAndAllowWhileIdle(type,at,p);}
    public void cancel(PendingIntent p){if(denyCancel)throw new IllegalStateException("alarm service unavailable");alarms.remove(p);}
}
'@
    'android/carstatus/CarStatus.java' = 'package android.carstatus; public class CarStatus { public static int state=-1; public int getAccStatus(){return state;} }'
    'com/codex/hn7camerastream/CameraStreamService.java' = 'package com.codex.hn7camerastream; public class CameraStreamService { public static final String ACTION_BOOTSTRAP_RETRY="retry"; public static CameraStreamService instance; public static CameraStreamService getRunningInstance(){return instance;} }'
    'com/codex/hn7camerastream/BootBootstrapActivity.java' = 'package com.codex.hn7camerastream; public class BootBootstrapActivity {}'
    'com/codex/hn7camerastream/StockDvrAccessGuard.java' = 'package com.codex.hn7camerastream; public class StockDvrAccessGuard { public static void clearSession(android.content.Context c){} }'
    'com/codex/hn7camerastream/StockDvrParkingLock.java' = 'package com.codex.hn7camerastream; public class StockDvrParkingLock { public static void disableSavedSetting(android.content.Context c){} }'
    'com/hcn/dvr/utils/DataHelper.java' = @'
package com.hcn.dvr.utils;
import android.content.Context;
public class DataHelper {
    public static DataHelper instance=new DataHelper(); public boolean auto,usb,audio,failStart; public Controller cameraController=new Controller();
    public static DataHelper getInstance(Context c){return instance;} public boolean getRecordAutoSave(){return auto;} public boolean isReadyForRecord(){return usb;}
    public boolean getEnableAudioRecord(){return audio;} public String getRecordSavePath(){return "/storage/udisk1/dvr";}
    public static class Controller {
        public boolean opened,encoding; public int opens,starts; public String path;
        public boolean isOpened(){return opened;} public boolean isEncoding(){return encoding;}
        public void open(){opens++;} public void startEncoding(String p){starts++;path=p;if(!instance.failStart)encoding=true;}
    }
}
'@
    'com/hcn/dvr/utils/Utils.java' = 'package com.hcn.dvr.utils; public class Utils { public static boolean CAMERA_SIGNAL; }'
    'com/hcn/dvr/manager/AudioCaptureManager.java' = 'package com.hcn.dvr.manager; public class AudioCaptureManager { public static int starts; public static AudioCaptureManager getInstance(){return new AudioCaptureManager();} public void startCapture(){starts++;} }'
    'android/view/View.java' = @'
package android.view;
import android.content.Context; import android.util.AttributeSet; import android.graphics.Canvas;
public class View { public View(Context c){} public View(Context c,AttributeSet a){} public void setBackgroundColor(int c){} public boolean post(Runnable r){r.run();return true;} public void postInvalidate(){} public int getWidth(){return 100;} public int getHeight(){return 100;} protected void onDraw(Canvas c){} protected void onDetachedFromWindow(){} }
'@
    'android/graphics/Bitmap.java' = 'package android.graphics; public class Bitmap { boolean recycled; public boolean isRecycled(){return recycled;} public void recycle(){recycled=true;} public int getWidth(){return 10;} public int getHeight(){return 10;} }'
    'android/graphics/BitmapFactory.java' = 'package android.graphics; public class BitmapFactory { public static Bitmap decodeByteArray(byte[] b,int o,int l){return new Bitmap();} }'
    'android/graphics/Paint.java' = 'package android.graphics; public class Paint { public static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2; public Paint(int f){} }'
    'android/graphics/Color.java' = 'package android.graphics; public class Color { public static final int BLACK=0; }'
    'android/graphics/Rect.java' = 'package android.graphics; public class Rect { public Rect(int a,int b,int c,int d){} }'
    'android/graphics/Canvas.java' = 'package android.graphics; public class Canvas { public void drawBitmap(Bitmap b,Rect s,Rect d,Paint p){} }'
}
foreach ($entry in $Stubs.GetEnumerator()) {
    $Path = Join-Path $Work $entry.Key
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    [IO.File]::WriteAllText($Path, $entry.Value, $Utf8)
}
foreach ($Name in @('EnhancedDvrBootstrap', 'BootReceiver', 'AtotoAccState', 'MjpegView', 'StockDvrAutoRecorder')) {
    Copy-Item -LiteralPath (Join-Path $ProjectRoot "app\src\com\codex\hn7camerastream\$Name.java") -Destination (Join-Path $Work "com\codex\hn7camerastream\$Name.java") -Force
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'StartupRecoveryTest.java') -Destination (Join-Path $Work 'com\codex\hn7camerastream\StartupRecoveryTest.java') -Force
$Sources = Get-ChildItem -LiteralPath $Work -Recurse -Filter '*.java' | ForEach-Object FullName
& (Join-Path $JavaRoot 'bin\javac.exe') -encoding UTF-8 -d $Classes $Sources
if ($LASTEXITCODE -ne 0) { throw 'Startup verification compile failed.' }
& (Join-Path $JavaRoot 'bin\java.exe') -cp $Classes com.codex.hn7camerastream.StartupRecoveryTest
if ($LASTEXITCODE -ne 0) { throw 'Startup recovery verification failed.' }
